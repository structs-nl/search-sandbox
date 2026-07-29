package nl.structs;

import java.io.IOException;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.FacetsConfig.DrillDownTermsIndexing;

public class ConfigHelper {

  private ConfigHelper() {
  }

  public static FacetsConfig loadFacetConfig(JsonNode jsonconfig) throws IOException {

    var config = new FacetsConfig();

    var facetsNode = jsonconfig.get("facets");

    if (!facetsNode.isArray()) {
      return config;
    }

    for (var field : facetsNode) {

      // TODO: dimensions should be unique

      for (var dim: field.get("dimensions")) {
        if(dim.isTextual()) {
          
          var dimname = dim.asText();

          var hierarchicalNode = field.get("hierarchical");
          if (hierarchicalNode != null && !hierarchicalNode.isMissingNode()) {
            config.setHierarchical(dimname, hierarchicalNode.asBoolean());
          }

          var multivaluedNode = field.get("multivalued");
          if (multivaluedNode != null && !multivaluedNode.isMissingNode()) {
            config.setMultiValued(dimname, multivaluedNode.asBoolean());
          }

          var drilldownNode = field.get("drillDownTermsIndexing");
          if (drilldownNode != null && !drilldownNode.isMissingNode() && drilldownNode.isTextual()) {
            config.setDrillDownTermsIndexing(dimname, parseDrillDownTermsIndexing(drilldownNode.asText()));
          }

          var requireDimCountNode = field.get("requireDimCount");
          if (requireDimCountNode != null && !requireDimCountNode.isMissingNode()) {
            config.setRequireDimCount(dimname, requireDimCountNode.asBoolean());
          }

          var indexFieldNameNode = field.get("indexFieldName");
          if (indexFieldNameNode != null && !indexFieldNameNode.isMissingNode() && indexFieldNameNode.isTextual()) {
            config.setIndexFieldName(dimname, indexFieldNameNode.asText());
          }
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
