package com.complone.base.impl;

import com.complone.base.utils.Closeables;
import com.complone.base.db.Slices;
import com.complone.base.include.Slice;
import com.complone.base.include.SliceInput;
import com.complone.base.include.SliceOutput;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.complone.base.impl.LogConstants.BLOCK_SIZE;
import static com.complone.base.impl.LogConstants.HEADER_SIZE;
import static java.util.Objects.requireNonNull;

public class FileChannelLogWriter
        implements LogWriter
{
    private final File file;
    private final long fileNumber;
    private final FileChannel fileChannel;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Current offset in the current block
     */
    private int blockOffset;

    public FileChannelLogWriter(File file, long fileNumber)
            throws FileNotFoundException
    {
        requireNonNull(file, "file is null");
        checkArgument(fileNumber >= 0, "fileNumber is negative");

        this.file = file;
        this.fileNumber = fileNumber;
        this.fileChannel = new FileOutputStream(file).getChannel();
    }

    @Override
    public boolean isClosed()
    {
        return closed.get();
    }

    @Override
    public synchronized void close()
    {
        closed.set(true);

        try {
            fileChannel.force(true);
        }
        catch (IOException ignored) {
        }

        Closeables.closeQuietly(fileChannel);
    }

    @Override
    public synchronized void delete()
    {
        closed.set(true);

        Closeables.closeQuietly(fileChannel);

        file.delete();
    }

    @Override
    public File getFile()
    {
        return file;
    }

    @Override
    public long getFileNumber()
    {
        return fileNumber;
    }

    @Override
    public synchronized void addRecord(Slice record, boolean force)
            throws IOException
    {
        checkState(!closed.get(), "Log has been closed");

        SliceInput sliceInput = record.input();

        // ?????????begin=true?????????????????????log record
        // ??????????????????log???first??? middle??? last block???
        boolean begin = true;

        // ????????????do{}while????????????????????????????????????????????????????????????
        do {
            int bytesRemainingInBlock = BLOCK_SIZE - blockOffset;
            checkState(bytesRemainingInBlock >= 0);

            // ?????????????????? < 7?????????????????????block
            if (bytesRemainingInBlock < HEADER_SIZE) {
                if (bytesRemainingInBlock > 0) {
                    fileChannel.write(ByteBuffer.allocate(bytesRemainingInBlock));
                }
                blockOffset = 0;
                bytesRemainingInBlock = BLOCK_SIZE - blockOffset;
            }

            // ??????????????????????????????7?????????
            int bytesAvailableInBlock = bytesRemainingInBlock - HEADER_SIZE;
            checkState(bytesAvailableInBlock >= 0);

            // ??????log record?????????????????????
            boolean end;
            int fragmentLength;
            if (sliceInput.available() > bytesAvailableInBlock) {
                end = false;
                fragmentLength = bytesAvailableInBlock;
            }
            else {
                end = true;
                fragmentLength = sliceInput.available();
            }

            // ????????????????????????log type
            LogType type;
            if (begin && end) {
                type = LogType.FULL;
            }
            else if (begin) {
                type = LogType.FIRST;
            }
            else if (end) {
                type = LogType.LAST;
            }
            else {
                type = LogType.MIDDLE;
            }

            // ??????log
            writeChunk(type, sliceInput.readSlice(fragmentLength));

            // ?????????????????????block????????????begin??????
            begin = false;
        } while (sliceInput.isReadable());

        if (force) {
            fileChannel.force(false);
        }
    }

    private void writeChunk(LogType type, Slice slice)
            throws IOException
    {
        checkArgument(slice.length() <= 0xffff, "length %s is larger than two bytes", slice.length());
        checkArgument(blockOffset + HEADER_SIZE <= BLOCK_SIZE);

        // ??????header
        Slice header = newLogRecordHeader(type, slice, slice.length());

        // ???header???data??????log
        header.getBytes(0, fileChannel, header.length());
        slice.getBytes(0, fileChannel, slice.length());

        blockOffset += HEADER_SIZE + slice.length();
    }

    private Slice newLogRecordHeader(LogType type, Slice slice, int length)
    {
        int crc = Logs.getCrc32C(type.getPersistentId(), slice.getData(), slice.getOffset(), length);

        // ?????????header
        SliceOutput header = Slices.allocate(HEADER_SIZE).output();
        header.writeInt(crc);
        header.writeByte((byte) (length & 0xff));
        header.writeByte((byte) (length >>> 8));
        header.writeByte((byte) (type.getPersistentId()));

        return header.slice();
    }
}