package ch.interlis.ibx;

import ch.interlis.ibx.api.*;
import ch.interlis.ibx.container.ContainerWriter;
import ch.interlis.ibx.iox.*;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.iom_j.xtf.*;
import ch.interlis.iox.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class IbxWriterTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();
  private ModelBridge bridge() throws Exception {
    WriterOptions o = new WriterOptions();
    o.modelFiles.add(Paths.get("src/test/resources/Tiny.ili").toAbsolutePath().toString());
    return ModelBridge.load(Paths.get("src/test/resources/tiny.xtf"), o, tmp.newFolder().toPath());
  }
  @Test public void directlyWritesEventsWithAllCompressions() throws Exception {
    for (String compression : Arrays.asList("none", "deflate", "zstd")) {
      ModelBridge b = bridge();
      WriterOptions o = new WriterOptions(); o.compression = compression;
      Path target = tmp.getRoot().toPath().resolve(compression+".ibx");
      Xtf24Reader r = new Xtf24Reader(new File("src/test/resources/tiny.xtf")); r.setModel(b.model);
      try (IbxWriter w = new IbxWriter(target,b.model,o)) {
        w.setModels(new String[]{"Tiny"});
        IoxEvent e;
        while ((e=r.read())!=null) { w.write(e); if(e instanceof EndTransferEvent) break; }
        w.flush();
      } finally { r.close(); }
      try (IbxContainer c=IbxContainer.open(target); Fragment f=c.getTopic("Tiny.Data")) {
        assertEquals(3, f.baskets().count()); assertEquals(3, f.objects().count());
        assertEquals("test sender",c.metadata().sender);
      }
    }
  }
  @Test public void incompleteOrInvalidTransferNeverPublishes() throws Exception {
    TransferDescription td=bridge().model;
    for (int failure=0;failure<4;failure++) {
      Path p=tmp.getRoot().toPath().resolve("bad"+failure+".ibx");
      try(IbxWriter w=new IbxWriter(p,td,new WriterOptions())) {
        if(failure==0) { w.write(new ch.interlis.iox_j.StartTransferEvent()); w.flush(); assertFalse(Files.exists(p)); }
        else {
          try {
            if(failure>1) w.write(new ch.interlis.iox_j.StartTransferEvent());
            if(failure==3) w.write(new ch.interlis.iox_j.StartBasketEvent("Tiny.Data","b"));
            w.write(failure==2 ? new ch.interlis.iox_j.StartTransferEvent() : new ch.interlis.iox_j.EndTransferEvent());
            fail("invalid sequence accepted");
          } catch(IoxException expected) { }
        }
      }
      assertFalse(Files.exists(p));
    }
    try(java.util.stream.Stream<Path> paths=Files.list(tmp.getRoot().toPath())) {
      assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith(".ibx-")));
    }
  }
  @Test public void existingDestinationSurvivesAbortAndOverwriteIsExplicit() throws Exception {
    TransferDescription td=bridge().model;
    Path p=tmp.newFile().toPath(); byte[] original={1,2,3}; Files.write(p,original);
    try(IbxWriter w=new IbxWriter(p,td,new WriterOptions())) {
      try { w.write(new ch.interlis.iox_j.StartTransferEvent()); fail(); } catch(IoxException expected) {}
    }
    WriterOptions overwrite=new WriterOptions(); overwrite.overwrite=true;
    try(IbxWriter w=new IbxWriter(p,td,overwrite)) { w.write(new ch.interlis.iox_j.StartTransferEvent()); }
    assertArrayEquals(original,Files.readAllBytes(p));
  }
  @Test public void xtf23RoundtripRetainsVersionAndSemantics() throws Exception {
    Path ili=tmp.newFile("Tiny.ili").toPath();
    Files.write(ili,new String(Files.readAllBytes(Paths.get("src/test/resources/Tiny.ili")),"UTF-8").replace("INTERLIS 2.4;","INTERLIS 2.3;").getBytes("UTF-8"));
    ch.interlis.ili2c.config.Configuration cfg=new ch.interlis.ili2c.config.Configuration();
    cfg.addFileEntry(new ch.interlis.ili2c.config.FileEntry(ili.toString(),ch.interlis.ili2c.config.FileEntryKind.ILIMODELFILE));
    TransferDescription td=ch.interlis.ili2c.Main.runCompiler(cfg); assertNotNull(td);
    Path input=tmp.newFile("input.xtf").toPath();
    // Independently encode the existing rich event fixture using iox-ili's 2.3 writer.
    Xtf24Reader r=new Xtf24Reader(new File("src/test/resources/tiny.xtf")); r.setModel(bridge().model);
    XtfWriter out=new XtfWriter(input.toFile(),td);
    try {
      IoxEvent e;
      while((e=r.read())!=null) {
        if(e instanceof StartTransferEvent) e=new ch.interlis.iox_j.StartTransferEvent("test sender","original comment","2.3");
        out.write(e); if(e instanceof EndTransferEvent) break;
      }
    } finally { out.close(); r.close(); }
    WriterOptions o=new WriterOptions(); o.modelFiles.add(ili.toString());
    Path ibx=tmp.getRoot().toPath().resolve("data.ibx"); ContainerWriter.create(input,ibx,o);
    Path roundtrip=tmp.newFile("roundtrip.xtf").toPath();
    try(IbxContainer c=IbxContainer.open(ibx); OutputStream output=Files.newOutputStream(roundtrip)) {
      assertEquals("2.3",c.metadata().version); c.export(output);
    }
    ModelBridge b=ModelBridge.load(input,o,tmp.newFolder().toPath());
    ch.interlis.ibx.benchmark.RoundtripVerifier.verify(input,roundtrip,b,tmp.newFolder().toPath());
    Path again=tmp.getRoot().toPath().resolve("again.ibx"); ContainerWriter.create(roundtrip,again,o);
  }
}
