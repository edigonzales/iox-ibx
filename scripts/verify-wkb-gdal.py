#!/usr/bin/env python3
"""Independent GDAL read and exact ISO reserialization against explicit WKT fixtures."""
import pathlib
import sys
from osgeo import ogr, gdal

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "build/wkb-fixtures")
files = sorted(root.glob("*.wkb"))
assert len(files) == 22, f"Expected 22 fixtures, found {len(files)}"
for path in files:
    actual = ogr.CreateGeometryFromWkb(path.read_bytes())
    expected = ogr.CreateGeometryFromWkt(path.with_suffix(".wkt").read_text())
    assert actual is not None and expected is not None, path
    assert actual.GetGeometryType() == expected.GetGeometryType(), path
    assert actual.GetCoordinateDimension() == expected.GetCoordinateDimension(), path
    assert bytes(actual.ExportToIsoWkb(ogr.wkbNDR)) == bytes(expected.ExportToIsoWkb(ogr.wkbNDR)), path
    assert bytes(actual.ExportToIsoWkb(ogr.wkbNDR)) == path.read_bytes(), path
print(f"GDAL {gdal.VersionInfo('--version')}: {len(files)} XY/XYZ linear and curve fixtures verified")
