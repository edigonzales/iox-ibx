# iox-ibx

Java IOX reader/writer library for INTERLIS Binary eXchange (IBX), container format 4.
Supports INTERLIS 2.3 and 2.4 FULL transfers, bounded-memory external sorting,
lossless IOM objects, optional ISO-WKB geometry, navigation and spatial indexes.
INITIAL/UPDATE transfers are rejected. Java 21 is the supported build and runtime.

```java
try (IbxWriter writer = new IbxWriter(output.toPath(), compiledModel, new WriterOptions())) {
    writer.setModels(new String[] {"MyModel"}); // optional, before StartTransferEvent
    writer.write(startTransfer);
    // StartBasketEvent, ObjectEvent*, EndBasketEvent, ...
    writer.write(endTransfer);
}
```

`ch.interlis.ibx.iox.IbxWriter` implements `IoxWriter`. Objects are encoded when
`write` is called; callers may reuse their event/object instances afterwards.
`EndTransferEvent` finalizes and publishes the file. `flush` does not finalize.
`close` without an end event aborts and removes temporary files. Existing targets
are protected unless `WriterOptions.overwrite` is explicitly enabled.

`ContainerWriter.create` is the XTF file adapter. `IbxContainer` provides sequential,
selective and remote readers; `SpatialIndex.add` adds optional indexes after the
core file is complete. An index failure leaves the core file available.
Package names are retained from ilicontainer. CLI, benchmarks, bridge and QGIS
remain in [ilicontainer](https://github.com/edigonzales/ilicontainer).

## Build and publish

```sh
./gradlew build
./gradlew publish
```

Maven coordinates: `ch.interlis:iox-ibx:0.1.0-SNAPSHOT`.
Repository: `https://jars.interlis.guru/snapshots`.
Publication includes JAR, sources JAR and POM. Configure `mavenRepoUser` and
`mavenRepoPassword` as Gradle properties, or `MAVEN_REPO_USER` and
`MAVEN_REPO_PASSWORD` as environment variables. GitHub Actions uses repository
secrets `INTERLIS_MAVEN_USERNAME` and `INTERLIS_MAVEN_TOKEN` and publishes after
successful tests on main. Pull requests only build/test.

`Source-Commit` in each JAR manifest identifies its source revision. See
[THIRD_PARTY.md](THIRD_PARTY.md) for source attribution.
