package com.ouyang.asbridge;

import java.nio.file.Path;

record ResolvedOpenRequest(Path absolutePath, int line, int column) {
}
