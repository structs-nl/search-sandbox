package nl.structs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.FacetsConfig.DrillDownTermsIndexing;

public class FacetsConfigHelper {

  private FacetsConfigHelper() {}

  public static FacetsConfig loadFacetConfig(Path facetConfigPath) throws IOException {
    var config = new FacetsConfig();

    if (facetConfigPath == null || !Files.exists(facetConfigPath)) {
      return config;
    }

    try (var input = Files.newInputStream(facetConfigPath)) {
      var tree = new YAMLMapper().readTree(input);
      var facetsNode = tree.has("facets") ? tree.get("facets") : tree;

      if (!facetsNode.isObject()) {
        return config;
      }

      for (var field : facetsNode.properties()) {
        var dimension = field.getKey();
        var dimensionConfig = field.getValue();

        if (!dimensionConfig.isObject()) {
          continue;
        }

        var hierarchicalNode = dimensionConfig.get("hierarchical");
        if (hierarchicalNode != null && !hierarchicalNode.isMissingNode()) {
          config.setHierarchical(dimension, hierarchicalNode.asBoolean());
        }

        var multivaluedNode = dimensionConfig.get("multivalued");
        if (multivaluedNode != null && !multivaluedNode.isMissingNode()) {
          config.setMultiValued(dimension, multivaluedNode.asBoolean());
        }

        var drilldownNode = dimensionConfig.get("drillDownTermsIndexing");
        if (drilldownNode != null && !drilldownNode.isMissingNode() && drilldownNode.isTextual()) {
          config.setDrillDownTermsIndexing(dimension, parseDrillDownTermsIndexing(drilldownNode.asText()));
        }

        var requireDimCountNode = dimensionConfig.get("requireDimCount");
        if (requireDimCountNode != null && !requireDimCountNode.isMissingNode()) {
          config.setRequireDimCount(dimension, requireDimCountNode.asBoolean());
        }

        var indexFieldNameNode = dimensionConfig.get("indexFieldName");
        if (indexFieldNameNode != null && !indexFieldNameNode.isMissingNode() && indexFieldNameNode.isTextual()) {
          config.setIndexFieldName(dimension, indexFieldNameNode.asText());
        }
      }
    }

    return config;
  }

  private static DrillDownTermsIndexing parseDrillDownTermsIndexing(String value) {
    return switch (value.trim().toUpperCase(Locale.ROOT)) {
      case "NONE" -> DrillDownTermsIndexing.NONE;
      case "FULL_PATH_ONLY" -> DrillDownTermsIndexing.FULL_PATH_ONLY;
      case "ALL_PATHS_NO_DIM" -> DrillDownTermsIndexing.ALL_PATHS_NO_DIM;
      case "DIMENSION_AND_FULL_PATH" -> DrillDownTermsIndexing.DIMENSION_AND_FULL_PATH;
      case "ALL" -> DrillDownTermsIndexing.ALL;
      default -> throw new IllegalArgumentException("unsupported drillDownTermsIndexing value '" + value + "'");
    };
  }
}
