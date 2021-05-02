package com.onthegomap.flatmap.read;

import com.onthegomap.flatmap.FileUtils;
import com.onthegomap.flatmap.Profile;
import com.onthegomap.flatmap.SourceFeature;
import com.onthegomap.flatmap.geo.GeoUtils;
import com.onthegomap.flatmap.monitoring.Stats;
import com.onthegomap.flatmap.worker.Topology;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NaturalEarthReader extends Reader {

  private static final Logger LOGGER = LoggerFactory.getLogger(NaturalEarthReader.class);

  private final Connection conn;
  private Path extracted;

  public NaturalEarthReader(Path input, Profile profile, Stats stats) {
    this(input, null, profile, stats);
  }

  public NaturalEarthReader(Path input, Path tmpDir, Profile profile, Stats stats) {
    super(profile, stats);
    try {
      conn = open(input, tmpDir);
    } catch (IOException | SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private Connection open(Path path, Path tmpLocation) throws IOException, SQLException {
    String uri = "jdbc:sqlite:" + path.toAbsolutePath();
    if (FileUtils.hasExtension(path, "zip")) {
      Path toOpen = tmpLocation == null ? Files.createTempFile("sqlite", "natearth") : tmpLocation;
      extracted = toOpen;
      try (var zipFs = FileSystems.newFileSystem(path)) {
        var zipEntry = FileUtils.walkFileSystem(zipFs)
          .filter(entry -> FileUtils.hasExtension(entry, "sqlite"))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("No .sqlite file found inside " + path));
        LOGGER.info("unzipping " + path.toAbsolutePath() + " to " + extracted);
        Files.copy(Files.newInputStream(zipEntry), extracted, StandardCopyOption.REPLACE_EXISTING);
        extracted.toFile().deleteOnExit();
      }
      uri = "jdbc:sqlite:" + toOpen.toAbsolutePath();
    }
    return DriverManager.getConnection(uri);
  }

  private List<String> tableNames() {
    List<String> result = new ArrayList<>();
    try (ResultSet rs = conn.getMetaData().getTables(null, null, null, null)) {
      while (rs.next()) {
        String table = rs.getString("TABLE_NAME");
        result.add(table);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    return result;
  }

  @Override
  public long getCount() {
    long count = 0;
    for (String table : tableNames()) {
      try (
        var stmt = conn.createStatement();
        var result = stmt.executeQuery("select count(*) from " + table + " where GEOMETRY is not null;")
      ) {
        count += result.getLong(1);
      } catch (SQLException e) {
        // maybe no GEOMETRY column?
      }
    }
    return count;
  }

  @Override
  public Topology.SourceStep<SourceFeature> read() {
    return next -> {
      var tables = tableNames();
      for (int i = 0; i < tables.size(); i++) {
        String table = tables.get(i);
        LOGGER.trace("Naturalearth loading " + i + "/" + tables.size() + ": " + table);

        try (Statement statement = conn.createStatement()) {
          ResultSet rs = statement.executeQuery("select * from " + table + ";");
          String[] column = new String[rs.getMetaData().getColumnCount()];
          int geometryColumn = -1;
          for (int c = 0; c < column.length; c++) {
            String name = rs.getMetaData().getColumnName(c + 1);
            column[c] = name;
            if ("GEOMETRY".equals(name)) {
              geometryColumn = c;
            }
          }
          if (geometryColumn >= 0) {
            while (rs.next()) {
              byte[] geometry = rs.getBytes(geometryColumn + 1);
              if (geometry == null) {
                continue;
              }
              Geometry geom = GeoUtils.wkbReader.read(geometry);
              SourceFeature readerGeometry = new ReaderFeature(geom, column.length - 1);
              for (int c = 0; c < column.length; c++) {
                if (c != geometryColumn) {
                  Object value = rs.getObject(c + 1);
                  String key = column[c];
                  readerGeometry.setTag(key, value);
                }
              }
              next.accept(readerGeometry);
            }
          }
        }
      }
    };
  }

  @Override
  public void close() {
    try {
      conn.close();
    } catch (SQLException e) {
      LOGGER.error("Error closing sqlite file", e);
    }
    if (extracted != null) {
      try {
        Files.deleteIfExists(extracted);
      } catch (IOException e) {
        LOGGER.error("Error deleting temp file", e);
      }
    }
  }
}