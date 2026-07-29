package nl.structs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuerierTest {

  @TempDir
  Path tempDir;

  @Test
  void initializesEmptyIndexWhenDirectoriesAreEmpty() throws Exception {
    var indexPath = tempDir.resolve("index");
    var taxPath = tempDir.resolve("tax");
    Files.createDirectories(indexPath);
    Files.createDirectories(taxPath);

    assertDoesNotThrow(() -> Indexer.initializeEmptyIndexesIfNeeded(indexPath, taxPath));

    try (var indexdir = FSDirectory.open(indexPath)) {
      assertDoesNotThrow(() -> DirectoryReader.open(indexdir));
    }
  }
}
