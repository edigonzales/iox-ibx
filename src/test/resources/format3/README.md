# Binärfixtures

`binary-index.ilic` bewahrt das ursprüngliche Format-3-Fixture unverändert als
historischen Nachweis. Aktuelle Reader lehnen seine Familie ab.

`../format5/binary-index.ibx` besitzt den unabhängig aktualisierten Header `IBXCONT1`,
Version 5, den Footer `IBXFOOT1`, Version 5 und neu berechnete Footer-CRC32.
Die zwei F-Schlüssel (0→01, 1→02) und die präfixkomprimierte Indexseite sind
unverändert. Format5Test vergleicht die vollständigen erzeugten Bytes mit diesem
Fixture und prüft zusätzlich die Ablehnung der alten Formatversionen einschliesslich Format 4.
