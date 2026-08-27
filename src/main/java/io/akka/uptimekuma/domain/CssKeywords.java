package io.akka.uptimekuma.domain;

/**
 * The CSS colour keywords, as red-green-blue triples.
 *
 * <p>A badge's colour may be given as a keyword, and whether the text on it is drawn white or dark
 * grey is decided from its luminance — so a keyword that does not resolve here would silently give
 * a badge black-on-black. The table is the CSS specification's own, in the same form the source's
 * colour library carries it.
 */
final class CssKeywords {

  private CssKeywords() {}

  static int[] rgb(String name) {
    return switch (name) {
      case "aliceblue" -> new int[] {240, 248, 255};
      case "antiquewhite" -> new int[] {250, 235, 215};
      case "aqua" -> new int[] {0, 255, 255};
      case "aquamarine" -> new int[] {127, 255, 212};
      case "azure" -> new int[] {240, 255, 255};
      case "beige" -> new int[] {245, 245, 220};
      case "bisque" -> new int[] {255, 228, 196};
      case "black" -> new int[] {0, 0, 0};
      case "blanchedalmond" -> new int[] {255, 235, 205};
      case "blue" -> new int[] {0, 0, 255};
      case "blueviolet" -> new int[] {138, 43, 226};
      case "brown" -> new int[] {165, 42, 42};
      case "burlywood" -> new int[] {222, 184, 135};
      case "cadetblue" -> new int[] {95, 158, 160};
      case "chartreuse" -> new int[] {127, 255, 0};
      case "chocolate" -> new int[] {210, 105, 30};
      case "coral" -> new int[] {255, 127, 80};
      case "cornflowerblue" -> new int[] {100, 149, 237};
      case "cornsilk" -> new int[] {255, 248, 220};
      case "crimson" -> new int[] {220, 20, 60};
      case "cyan" -> new int[] {0, 255, 255};
      case "darkblue" -> new int[] {0, 0, 139};
      case "darkcyan" -> new int[] {0, 139, 139};
      case "darkgoldenrod" -> new int[] {184, 134, 11};
      case "darkgray" -> new int[] {169, 169, 169};
      case "darkgreen" -> new int[] {0, 100, 0};
      case "darkgrey" -> new int[] {169, 169, 169};
      case "darkkhaki" -> new int[] {189, 183, 107};
      case "darkmagenta" -> new int[] {139, 0, 139};
      case "darkolivegreen" -> new int[] {85, 107, 47};
      case "darkorange" -> new int[] {255, 140, 0};
      case "darkorchid" -> new int[] {153, 50, 204};
      case "darkred" -> new int[] {139, 0, 0};
      case "darksalmon" -> new int[] {233, 150, 122};
      case "darkseagreen" -> new int[] {143, 188, 143};
      case "darkslateblue" -> new int[] {72, 61, 139};
      case "darkslategray" -> new int[] {47, 79, 79};
      case "darkslategrey" -> new int[] {47, 79, 79};
      case "darkturquoise" -> new int[] {0, 206, 209};
      case "darkviolet" -> new int[] {148, 0, 211};
      case "deeppink" -> new int[] {255, 20, 147};
      case "deepskyblue" -> new int[] {0, 191, 255};
      case "dimgray" -> new int[] {105, 105, 105};
      case "dimgrey" -> new int[] {105, 105, 105};
      case "dodgerblue" -> new int[] {30, 144, 255};
      case "firebrick" -> new int[] {178, 34, 34};
      case "floralwhite" -> new int[] {255, 250, 240};
      case "forestgreen" -> new int[] {34, 139, 34};
      case "fuchsia" -> new int[] {255, 0, 255};
      case "gainsboro" -> new int[] {220, 220, 220};
      case "ghostwhite" -> new int[] {248, 248, 255};
      case "gold" -> new int[] {255, 215, 0};
      case "goldenrod" -> new int[] {218, 165, 32};
      case "gray" -> new int[] {128, 128, 128};
      case "green" -> new int[] {0, 128, 0};
      case "greenyellow" -> new int[] {173, 255, 47};
      case "grey" -> new int[] {128, 128, 128};
      case "honeydew" -> new int[] {240, 255, 240};
      case "hotpink" -> new int[] {255, 105, 180};
      case "indianred" -> new int[] {205, 92, 92};
      case "indigo" -> new int[] {75, 0, 130};
      case "ivory" -> new int[] {255, 255, 240};
      case "khaki" -> new int[] {240, 230, 140};
      case "lavender" -> new int[] {230, 230, 250};
      case "lavenderblush" -> new int[] {255, 240, 245};
      case "lawngreen" -> new int[] {124, 252, 0};
      case "lemonchiffon" -> new int[] {255, 250, 205};
      case "lightblue" -> new int[] {173, 216, 230};
      case "lightcoral" -> new int[] {240, 128, 128};
      case "lightcyan" -> new int[] {224, 255, 255};
      case "lightgoldenrodyellow" -> new int[] {250, 250, 210};
      case "lightgray" -> new int[] {211, 211, 211};
      case "lightgreen" -> new int[] {144, 238, 144};
      case "lightgrey" -> new int[] {211, 211, 211};
      case "lightpink" -> new int[] {255, 182, 193};
      case "lightsalmon" -> new int[] {255, 160, 122};
      case "lightseagreen" -> new int[] {32, 178, 170};
      case "lightskyblue" -> new int[] {135, 206, 250};
      case "lightslategray" -> new int[] {119, 136, 153};
      case "lightslategrey" -> new int[] {119, 136, 153};
      case "lightsteelblue" -> new int[] {176, 196, 222};
      case "lightyellow" -> new int[] {255, 255, 224};
      case "lime" -> new int[] {0, 255, 0};
      case "limegreen" -> new int[] {50, 205, 50};
      case "linen" -> new int[] {250, 240, 230};
      case "magenta" -> new int[] {255, 0, 255};
      case "maroon" -> new int[] {128, 0, 0};
      case "mediumaquamarine" -> new int[] {102, 205, 170};
      case "mediumblue" -> new int[] {0, 0, 205};
      case "mediumorchid" -> new int[] {186, 85, 211};
      case "mediumpurple" -> new int[] {147, 112, 219};
      case "mediumseagreen" -> new int[] {60, 179, 113};
      case "mediumslateblue" -> new int[] {123, 104, 238};
      case "mediumspringgreen" -> new int[] {0, 250, 154};
      case "mediumturquoise" -> new int[] {72, 209, 204};
      case "mediumvioletred" -> new int[] {199, 21, 133};
      case "midnightblue" -> new int[] {25, 25, 112};
      case "mintcream" -> new int[] {245, 255, 250};
      case "mistyrose" -> new int[] {255, 228, 225};
      case "moccasin" -> new int[] {255, 228, 181};
      case "navajowhite" -> new int[] {255, 222, 173};
      case "navy" -> new int[] {0, 0, 128};
      case "oldlace" -> new int[] {253, 245, 230};
      case "olive" -> new int[] {128, 128, 0};
      case "olivedrab" -> new int[] {107, 142, 35};
      case "orange" -> new int[] {255, 165, 0};
      case "orangered" -> new int[] {255, 69, 0};
      case "orchid" -> new int[] {218, 112, 214};
      case "palegoldenrod" -> new int[] {238, 232, 170};
      case "palegreen" -> new int[] {152, 251, 152};
      case "paleturquoise" -> new int[] {175, 238, 238};
      case "palevioletred" -> new int[] {219, 112, 147};
      case "papayawhip" -> new int[] {255, 239, 213};
      case "peachpuff" -> new int[] {255, 218, 185};
      case "peru" -> new int[] {205, 133, 63};
      case "pink" -> new int[] {255, 192, 203};
      case "plum" -> new int[] {221, 160, 221};
      case "powderblue" -> new int[] {176, 224, 230};
      case "purple" -> new int[] {128, 0, 128};
      case "rebeccapurple" -> new int[] {102, 51, 153};
      case "red" -> new int[] {255, 0, 0};
      case "rosybrown" -> new int[] {188, 143, 143};
      case "royalblue" -> new int[] {65, 105, 225};
      case "saddlebrown" -> new int[] {139, 69, 19};
      case "salmon" -> new int[] {250, 128, 114};
      case "sandybrown" -> new int[] {244, 164, 96};
      case "seagreen" -> new int[] {46, 139, 87};
      case "seashell" -> new int[] {255, 245, 238};
      case "sienna" -> new int[] {160, 82, 45};
      case "silver" -> new int[] {192, 192, 192};
      case "skyblue" -> new int[] {135, 206, 235};
      case "slateblue" -> new int[] {106, 90, 205};
      case "slategray" -> new int[] {112, 128, 144};
      case "slategrey" -> new int[] {112, 128, 144};
      case "snow" -> new int[] {255, 250, 250};
      case "springgreen" -> new int[] {0, 255, 127};
      case "steelblue" -> new int[] {70, 130, 180};
      case "tan" -> new int[] {210, 180, 140};
      case "teal" -> new int[] {0, 128, 128};
      case "thistle" -> new int[] {216, 191, 216};
      case "tomato" -> new int[] {255, 99, 71};
      case "turquoise" -> new int[] {64, 224, 208};
      case "violet" -> new int[] {238, 130, 238};
      case "wheat" -> new int[] {245, 222, 179};
      case "white" -> new int[] {255, 255, 255};
      case "whitesmoke" -> new int[] {245, 245, 245};
      case "yellow" -> new int[] {255, 255, 0};
      case "yellowgreen" -> new int[] {154, 205, 50};
      default -> null;
    };
  }
}
