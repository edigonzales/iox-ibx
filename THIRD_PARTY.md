# Third-party source attribution

The QGIS plugin ships no third-party source or binaries.  Its pure-Python
container reader (`qgis/ibx_browser/ibx/`) was written for this repository.
For zstd-compressed chunks it binds the Zstandard library that QGIS itself
ships (GDAL and Qt depend on it) at runtime via ``ctypes``; that library is
not redistributed.  Files using ``deflate`` or ``none`` need no native library.

The ISO-WKB curve codec and its regression fixtures adapt the SQL/MM type dispatch,
component encoding and control-point preservation from
`edigonzales/hop-geometry-type-plugin`, commit
`f4d4747a9e357026b3089a0ca2f2eec4a867993a` (2026).
Adapted files are in `geometry/` and `WkbFixturesTest.java`.
This adaptation replaces the Hop/JTS object graph with exact coordinate/component
records, adds strict ISO output and input bounds checks, and targets Java 8 APIs.
No Hop runtime or LocationTech JTS dependency is introduced.

## Original license

MIT License

Copyright (c) 2026 Stefan Ziegler

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
