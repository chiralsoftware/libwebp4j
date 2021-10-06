module Libwebp4j {
    requires jdk.incubator.foreign;
    requires java.logging;
    requires java.desktop;
    exports com.chiralsoftware.libwebp4j;
    uses javax.imageio.spi.ImageReaderSpi;
}
