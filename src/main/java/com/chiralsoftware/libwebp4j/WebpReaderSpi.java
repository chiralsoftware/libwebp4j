package com.chiralsoftware.libwebp4j;

import com.chiralsoftware.libwebp4j.impl.WebpImageReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.logging.Logger;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;

/**
 *
 */
public final class WebpReaderSpi extends ImageReaderSpi {

    private static final Logger LOG = Logger.getLogger(WebpReaderSpi.class.getName());
    
    public WebpReaderSpi() throws IOException {
        super();
        inputTypes = myInputTypes;
    }
    
    private static final byte[] webpFirstHeader = { 'R', 'I', 'F', 'F' };
    private static final byte[] webpSecondHeader = { 'W', 'E', 'B', 'P' };

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        LOG.fine("can i decode this? " + source);
        if(source instanceof byte[] ba) {
            // the smallest possible webp file is 26 bytes:
            // https://github.com/mathiasbynens/small/blob/master/webp.webp
            // although ImageMagick says that particular file is corrupt
            if(ba.length < 26) return false;
            for(int i = 0; i < webpFirstHeader.length; i++)
                if(webpFirstHeader[i] != ba[i]) return false;
            for(int i = 0; i < webpSecondHeader.length; i++ ) {
                if(webpSecondHeader[i] != ba[i + 4 + webpFirstHeader.length])
                    return false;
            }
            // TODO: also check file length
            return true;
        }
        if(source instanceof FileImageInputStream fiis) {
            final byte[] ba = new byte[26];
            fiis.read(ba);
            return canDecodeInput(ba);
        }
        if(source instanceof File file) {
            final byte[] ba = new byte[26];
            final FileInputStream fis = new FileInputStream(file);
            if(fis.read(ba) < 26) return false;
            fis.close();
            return canDecodeInput(ba);
        }
        LOG.info("Unsupported class: " + source.getClass().getName());
        return false;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) throws IOException {
        LOG.fine("Looking for a reader instance for this extension: " + extension);
        
        return new WebpImageReader(this);
    }
    
    /** @return  a copy of the valid input class types */
//    @Override
//    public Class[] getInputTypes() {
//        return Arrays.copyOf(inputTypes, inputTypes.length);
//    }
    
    private static final Class[] myInputTypes = new Class[] { 
        byte[].class,
        File.class, ImageInputStream.class
    };

    @Override
    public String getDescription(Locale locale) {
        return "ImageIO module for reading and writing WebP images using Google's libwebp";
    }
    
}
