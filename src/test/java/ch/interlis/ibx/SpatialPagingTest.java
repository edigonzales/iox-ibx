package ch.interlis.ibx;

import static org.junit.Assert.*;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.container.*;
import ch.interlis.ibx.spatial.SpatialIndex;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

@org.junit.runner.RunWith(org.junit.runners.Parameterized.class)
public class SpatialPagingTest {
  @org.junit.runners.Parameterized.Parameters(name = "geometry={0}")
  public static Collection<Object[]> encodings() {
    return Arrays.asList(new Object[][] {{"iom"}, {"wkb"}});
  }

  @org.junit.runners.Parameterized.Parameter public String geometryEncoding;
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void packedTreeMatchesIndependentGridAndReadsEachChunkOnce() throws Exception {
    String template = new String(Files.readAllBytes(ContainerTest.fixture("tiny.xtf")), "UTF-8");
    String prefix =
        template.substring(0, template.indexOf("<ili:datasection>"))
            + "<ili:datasection><Tiny:Data ili:bid=\"grid\">";
    Path input = tmp.newFile("grid.xtf").toPath(),
        file = tmp.getRoot().toPath().resolve("grid.ibx");
    try (BufferedWriter out = Files.newBufferedWriter(input)) {
      out.write(prefix);
      for (int x = 0; x < 64; x++)
        for (int y = 0; y < 64; y++)
          out.write(
              "<Tiny:Item ili:tid=\"p"
                  + x
                  + "_"
                  + y
                  + "\"><Tiny:point><geom:coord><geom:c1>"
                  + x
                  + "</geom:c1><geom:c2>"
                  + y
                  + "</geom:c2></geom:coord></Tiny:point></Tiny:Item>");
      out.write("</Tiny:Data></ili:datasection></ili:transfer>");
    }
    WriterOptions o = new WriterOptions();
    o.geometryEncoding = geometryEncoding;
    o.geometryCrs.put("Tiny.Data.Item.point", "EPSG:2056");
    o.modelFiles.add(ContainerTest.fixture("Tiny.ili").toString());
    o.chunkSize = 16384;
    o.embedModels = true;
    ContainerWriter.create(input, file, o);
    SpatialIndex.add(file, "Tiny.Data.Item", "point", "EPSG:2056");
    Set<String> expected = new HashSet<String>();
    for (int x = 10; x <= 20; x++) for (int y = 10; y <= 20; y++) expected.add("p" + x + "_" + y);
    try (IbxContainer c = IbxContainer.open(file)) {
      assertNotNull(c.metadata().sources.get(0).content);
      c.clearCache();
      c.metrics().reset();
      try (Fragment f =
          c.querySpatialCandidates("Tiny.Data.Item", "point", new BoundingBox(10, 10, 20, 20))) {
        assertEquals(
            expected,
            f.objects().map(v -> v.getObject().getobjectoid()).collect(Collectors.toSet()));
      }
      if ("wkb".equals(geometryEncoding))
        try (GisLayer layer = c.openLayer("Tiny.Data.Item.point");
            java.util.stream.Stream<GisFeature> features =
                layer.queryCandidates(new BoundingBox(10, 10, 20, 20))) {
          assertEquals(expected, features.map(GisFeature::getTid).collect(Collectors.toSet()));
        }
      assertTrue(c.metrics().indexPages > 1);
      assertTrue(c.metrics().chunksRead < 121);
      try (Fragment f =
          c.querySpatialCandidates("Tiny.Data.Item", "point", new BoundingBox(-10, -10, -1, -1))) {
        assertEquals(0, f.objects().count());
      }
      try (Fragment f =
          c.querySpatialCandidates("Tiny.Data.Item", "point", new BoundingBox(0, 0, 63, 63))) {
        assertEquals(4096, f.objects().count());
      }
    }
  }
}
