# IBX format 5 spatial pages

Breaking change: header and footer version are 5. Format 4 is rejected; regenerate from the original transfer. Object records, compression and 53-byte locations are unchanged.

Spatial-Seiten haben einen 8-Byte-Kopf: Codecversion u8 (1), Layout u8,
reserviert u16 (0), Eintragszahl i32 (nichtnegativ). Die Nutzdatenlänge muss
exakt 8 + Anzahl × Eintragsgrösse sein und darf 16384 Bytes nicht überschreiten.
Layout 1: Rechteckblatt, minX/minY/maxX/maxY als Float64 plus Location (85 Bytes).
Layout 2: Punktblatt, X/Y als Float64 plus Location (69 Bytes).
Layout 3: innerer Knoten, vier Float64-Bounds plus Kind-FrameRef (48 Bytes).
Frame-Typen 8 und 9 bleiben Blatt beziehungsweise innerer Knoten. Das Manifest
bleibt CBOR und enthält pro Index `leafLayout` (1 oder 2). Alle Koordinaten
müssen endlich sein; Bounds müssen geordnet sein. Kindreferenzen müssen
vollständig vor ihrer Elternseite liegen. Locations müssen auf gültige
Framebereiche zeigen und ein nichtnegatives Objektordinal besitzen.

Punktlayout gilt nur für einzelne CoordType-Attribute, nicht für MultiCoordType.
Die bisherige konservative Erweiterung um eine Float64-Einheit wird beim Aufbau
geprüft und für die Speicherung auf das exakte X/Y zurückgeführt. Decoder und
Eltern-Bounds rekonstruieren nextDown/nextUp; damit bleiben selbst Abfragen an
benachbarten Float64-Werten unverändert. Z bleibt ausschliesslich in der Geometrie.
Leere Indizes besitzen ein Blatt mit null Einträgen. STR-Streifenanzahl und
Seitenkapazität werden aus diesen festen Eintragsgrössen berechnet.

