package com.chiralsoftware.libwebp4j.impl;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.Iterator;
import java.util.List;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import java.util.logging.Logger;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.FileImageInputStream;
import static jdk.incubator.foreign.CLinker.C_INT;
import jdk.incubator.foreign.MemoryAccess;
import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import static jdk.incubator.foreign.MemorySegment.allocateNative;
import static jdk.incubator.foreign.ResourceScope.newImplicitScope;

/**
 * Read a Webp image
 */
public final class WebpImageReader extends ImageReader {

    private static final Logger LOG = Logger.getLogger(WebpImageReader.class.getName());

    private final LibWebp libWebp;
    private int width = -1, height = -1;
    
    private MemorySegment inputSegment = null;
    
    /** This method must be called to release native memory segments.
     If it is not called the application will leak memory. This is safe to call
     multiple times */
    @Override
    public void dispose() {
        super.dispose();
        LOG.fine("disposing resources of this reader");
//        if(inputSegment != null && inputSegment.isAlive()) inputSegment.close();
        inputSegment = null;
        width = height = -1;
    }
    
    public WebpImageReader(ImageReaderSpi irspi) {
        super(irspi);
        this.libWebp = LibWebp.getInstance();
    }

    /** This should decode a WebP container to see how many frames it contains. For now, always returns 1 */
    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        LOG.fine("FIXME: always returns 1");
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        if(width < 0) throw new IllegalStateException("this reader is not associated with any image.");
        return width;
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        if(height < 0) throw new IllegalStateException("this reader is not associated with any image");
        return height;
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        if(height < 0) throw new IllegalStateException("there's no image read");
        if(imageIndex != 0) throw new IndexOutOfBoundsException("this only supports single frame images");
        LOG.info("Ok, I need to get the ImageSpecifier for this");
        final ImageTypeSpecifier imageTypeSpecifier =
                ImageTypeSpecifier.createInterleaved(ColorSpace.getInstance(ColorSpace.TYPE_RGB), 
                        new int[] { 0,1,2} ,
                        DataBuffer.TYPE_BYTE,
                        true, false);
        
        return List.of(imageTypeSpecifier).iterator();
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        LOG.fine("not implemented because this is a single image");
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        if(imageIndex != 0) throw new IndexOutOfBoundsException("only one frame is supported");
        return new WebpMetaData();
    }
    
    /** Read in the image header to get image info */
    private void readHeader() {
        if(inputSegment == null) throw new NullPointerException("can't read the header of null input");

        
        final MemorySegment sizeSegment = 
                allocateNative(MemoryLayout.structLayout(C_INT.withName("width"), 
                        C_INT.withName("height")), newImplicitScope());
        
        try {
            // WebPGetInfo(const uint8_t* data, size_t data_size, int* width, int* height)
            libWebp.GetInfo.invoke(inputSegment.address(), (long) inputSegment.byteSize(), sizeSegment.address(), 
                    sizeSegment.address().addOffset(4));
        } catch (Throwable ex) {
            LOG.log(WARNING, "oh no!", ex);
        }

        width = MemoryAccess.getIntAtOffset(sizeSegment, 0);
        height = MemoryAccess.getIntAtOffset(sizeSegment, 4);
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        LOG.info("reading from the input stream! ignoring iimageIndex and param for now.");
        if(imageIndex != 0) 
            throw new IndexOutOfBoundsException("image index must be 0; it was: " + imageIndex);
        
        if(inputSegment == null) 
            throw new NullPointerException("inputSegment was null! was setInput called?");
        readHeader();
        LOG.info("Ok i read the header; size is: " + width  + ", " + height);
        final MemorySegment outputSegment = MemorySegment.allocateNative(width * height * 4, newImplicitScope());
        
        // uint8_t* WebPDecodeARGBInto(const uint8_t* data, size_t data_size,
        //                    uint8_t* output_buffer, int output_buffer_size, int output_stride);
        try {
            libWebp.DecodeARGBInto.invoke(inputSegment.address(), (long) inputSegment.byteSize(),
                outputSegment.address(), outputSegment.byteSize(), width * 4);
        } catch (Throwable ex) {
            LOG.log(WARNING, "oh no!", ex);
        }
        // now we can read the decoded bytes into a raster
        final BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        final WritableRaster writableRaster = bufferedImage.getRaster();
        final DataBuffer dataBuffer = writableRaster.getDataBuffer();
        LOG.finest("cool, I have a databuffer: "+ dataBuffer.getClass());
        LOG.finest("It has properties: size="+ dataBuffer.getSize() + ", banks=" + dataBuffer.getNumBanks());
        final DataBufferByte dataBufferByte = (DataBufferByte) dataBuffer;
        final byte[] bank1 = dataBufferByte.getData();
        LOG.finest("I got bank1, size is: " + bank1.length);
        final byte[] outputByteArray = outputSegment.toByteArray();
        LOG.fine("the outputByteArray size is: "+ outputByteArray.length);
        for(int i = 0; i < bank1.length / 4; i++) { 
            bank1[i * 4 + 0] = outputByteArray[i * 4 + 0]; // A
            bank1[i * 4 + 1] = outputByteArray[i * 4 + 3]; // B
            bank1[i * 4 + 2] = outputByteArray[i * 4 + 2]; // G
            bank1[i * 4 + 3] = outputByteArray[i * 4 + 1]; // R
        }
        return bufferedImage;
    }
    
    @Override
    public void setInput(Object input) {
        LOG.info("I got called with an input object: " + input.getClass().getName());
    }
    
    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        LOG.info("I got called, with ignoreMetaData = " + ignoreMetadata);
        if(input == null) throw new NullPointerException("can't set input to null; call dispose() if you wnat to dispose this.");
        if(inputSegment != null) 
            throw new IllegalStateException("call dispose() first!");
        
        if (input instanceof byte[] ba) {
            inputSegment = MemorySegment.ofArray(ba);
            return;
        }
        if(input instanceof FileInputStream fis) {
            // map this into memory to avoid copying
            final FileChannel fc = fis.getChannel();
            try {
                final MappedByteBuffer mappedByteBuffer = fc.map(MapMode.READ_ONLY, 0, fc.size());
                inputSegment = MemorySegment.ofByteBuffer(mappedByteBuffer);
            } catch (IOException ex) {
                LOG.log(INFO, "couldn't map file", ex);
            }
        }
        if(input instanceof FileImageInputStream fiis) {
            LOG.info("got a FileImageInputStream it is of type: " + fiis.getClass().getName());
            // unfortunately this is very inefficient - we have to load
            // the whole thing into a byte array
            final byte[] ba = new byte[(int)fiis.length()];
            inputSegment = MemorySegment.ofArray(ba);
            return;
        }
        
        throw new IllegalArgumentException("Unknown input type: " + input.getClass().getName());
    }

    @Override
    public void setInput(Object input, boolean isStreamable) {
        super.setInput(input, isStreamable);
        LOG.finest("calling set input with this input object: " + input.getClass().getName());
        if(input == null) throw new NullPointerException("can't set input to null; call dispose() if you wnat to dispose this.");
        if(inputSegment != null) 
            throw new IllegalStateException("call dispose() first!");
        
        if (input instanceof byte[] ba) {
//            inputSegment = MemorySegment.allocateNative(ba.length, newImplicitScope());
            inputSegment = MemorySegment.ofArray(ba);

            // fixme - we shouldn't have to copy bytes
//            final ByteBuffer bb = inputSegment.asByteBuffer();
//            bb.put(ba);

            return;
        }
        if(input instanceof FileInputStream fiis) {
            // map this into memory to avoid copying
            final FileChannel fc = fiis.getChannel();
            try {
                final MappedByteBuffer mappedByteBuffer = fc.map(MapMode.READ_ONLY, 0, fc.size());
                inputSegment = MemorySegment.ofByteBuffer(mappedByteBuffer);
            } catch (IOException ex) {
                LOG.log(INFO, "couldn't map file", ex);
            }
        }
        
        throw new IllegalArgumentException("Unknown input type: " + input.getClass().getName());
    }

}
