package nl.structs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.facet.FacetsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexerFacetConfigTest {

  @Test
  void loadsFacetSettingsFromYaml(@TempDir Path tempDir) throws Exception {
    var yamlPath = tempDir.resolve("facets.yaml");
    Files.writeString(yamlPath, """
        facets:
          parents:
            hierarchical: true
            multivalued: true
            drillDownTermsIndexing: ALL_PATHS_NO_DIM
            requireDimCount: true
            indexFieldName: parents_facet
          categories:
            hierarchical: false
            multivalued: false
        """);

    var config = FacetsConfigHelper.loadFacetConfig(yamlPath);

    var parentsConfig = config.getDimConfig("parents");
    assertTrue(parentsConfig.hierarchical);
    assertTrue(parentsConfig.multiValued);
    assertEquals(FacetsConfig.DrillDownTermsIndexing.ALL_PATHS_NO_DIM,
        parentsConfig.drillDownTermsIndexing);
    assertTrue(parentsConfig.requireDimCount);
    assertEquals("parents_facet", parentsConfig.indexFieldName);

    var categoriesConfig = config.getDimConfig("categories");
    assertFalse(categoriesConfig.hierarchical);
    assertFalse(categoriesConfig.multiValued);
  }
}
