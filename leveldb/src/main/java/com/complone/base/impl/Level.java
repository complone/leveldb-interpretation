package com.complone.base.impl;

import com.google.common.collect.Lists;
import com.complone.base.include.Slice;
import com.complone.base.table.UserComparator;
import com.complone.base.utils.InternalTableIterator;
import com.complone.base.utils.LevelIterator;

import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.complone.base.impl.SequenceNumber.MAX_SEQUENCE_NUMBER;
import static com.complone.base.impl.ValueType.VALUE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public class Level
        implements SeekingIterable<InternalKey, Slice>
{
    private final int levelNumber;
    private final TableCache tableCache;
    private final InternalKeyComparator internalKeyComparator;
    private final List<FileMetaData> files;

    public Level(int levelNumber, List<FileMetaData> files, TableCache tableCache, InternalKeyComparator internalKeyComparator)
    {
        checkArgument(levelNumber >= 0, "levelNumber is negative");
        requireNonNull(files, "files is null");
        requireNonNull(tableCache, "tableCache is null");
        requireNonNull(internalKeyComparator, "internalKeyComparator is null");

        this.files = new ArrayList<>(files);
        this.tableCache = tableCache;
        this.internalKeyComparator = internalKeyComparator;
        checkArgument(levelNumber >= 0, "levelNumber is negative");
        this.levelNumber = levelNumber;
    }

    public int getLevelNumber()
    {
        return levelNumber;
    }

    public List<FileMetaData> getFiles()
    {
        return files;
    }

    @Override
    public LevelIterator iterator()
    {
        return createLevelConcatIterator(tableCache, files, internalKeyComparator);
    }

    public static LevelIterator createLevelConcatIterator(TableCache tableCache, List<FileMetaData> files, InternalKeyComparator internalKeyComparator)
    {
        return new LevelIterator(tableCache, files, internalKeyComparator);
    }

    public LookupResult get(LookupKey key, ReadStats readStats)
    {
        if (files.isEmpty()) {
            return null;
        }

        List<FileMetaData> fileMetaDataList = new ArrayList<>(files.size());
        // FIXME level0 ??????.sst?????????????????????????????????key?????????
        //  ??????????????????level0??????sst?????????????????????key???sst????????????sst
        // ????????????level0 ??????.sst?????????key???????????????????????????????????????
        if (levelNumber == 0) {
            for (FileMetaData fileMetaData : files) {
                if (internalKeyComparator.getUserComparator().compare(key.getUserKey(), fileMetaData.getSmallest().getUserKey()) >= 0 &&
                        internalKeyComparator.getUserComparator().compare(key.getUserKey(), fileMetaData.getLargest().getUserKey()) <= 0) {
                    fileMetaDataList.add(fileMetaData);
                }
            }
        }
        else {
            // ????????????????????? key >= ikey?????????
            int index = ceilingEntryIndex(Lists.transform(files, FileMetaData::getLargest), key.getInternalKey(), internalKeyComparator);

            // ?????????????????????????????????????????????????????????sstable????????????key
            if (index >= files.size()) {
                return null;
            }

            // ?????????????????????key???????????????key
            FileMetaData fileMetaData = files.get(index);
            if (internalKeyComparator.getUserComparator().compare(key.getUserKey(), fileMetaData.getSmallest().getUserKey()) < 0) {
                return null;
            }

            // ????????????????????????????????????
            fileMetaDataList.add(fileMetaData);
        }

        FileMetaData lastFileRead = null;
        int lastFileReadLevel = -1;
        readStats.clear();
        for (FileMetaData fileMetaData : fileMetaDataList) {
            if (lastFileRead != null && readStats.getSeekFile() == null) {
                // ??????????????????????????????
                readStats.setSeekFile(lastFileRead);
                readStats.setSeekFileLevel(lastFileReadLevel);
            }

            lastFileRead = fileMetaData;
            lastFileReadLevel = levelNumber;

            // ??????fileMetaData??????file number??????tableCache??????????????????table???iterator
            InternalTableIterator iterator = tableCache.newIterator(fileMetaData);

            // ???table????????? >= lookup key????????????key
            iterator.seek(key.getInternalKey());

            if (iterator.hasNext()) {
                // ?????????block??????key
                Map.Entry<InternalKey, Slice> entry = iterator.next();
                InternalKey internalKey = entry.getKey();
                checkState(internalKey != null, "Corrupt key for %s", key.getUserKey().toString(UTF_8));

                // ???????????????key
                //  1. valuetype???value???????????????LookupResult
                //  1. valuetype???delete???????????????LookupResult
                if (key.getUserKey().equals(internalKey.getUserKey())) {
                    if (internalKey.getValueType() == ValueType.DELETION) {
                        return LookupResult.deleted(key);
                    }
                    else if (internalKey.getValueType() == VALUE) {
                        return LookupResult.ok(key, entry.getValue());
                    }
                }
            }
        }

        return null;
    }

    private static <T> int ceilingEntryIndex(List<T> list, T key, Comparator<T> comparator)
    {
        // ?????????????????????????????????????????????????????????????????????????????? (-(?????????) - 1)???
        // ???????????????????????????????????????????????????????????????????????????????????????????????????
        int insertionPoint = Collections.binarySearch(list, key, comparator);
        if (insertionPoint < 0) {
            insertionPoint = -(insertionPoint + 1);
        }
        return insertionPoint;
    }

    public boolean someFileOverlapsRange(Slice smallestUserKey, Slice largestUserKey)
    {
        InternalKey smallestInternalKey = new InternalKey(smallestUserKey, MAX_SEQUENCE_NUMBER, VALUE);
        int index = findFile(smallestInternalKey);

        UserComparator userComparator = internalKeyComparator.getUserComparator();
        return ((index < files.size()) &&
                userComparator.compare(largestUserKey, files.get(index).getLargest().getUserKey()) >= 0);
    }

    private int findFile(InternalKey targetKey)
    {
        if (files.isEmpty()) {
            return files.size();
        }

        // todo replace with Collections.binarySearch
        int left = 0;
        int right = files.size() - 1;

        // binary search restart positions to find the restart position immediately before the targetKey
        while (left < right) {
            int mid = (left + right) / 2;

            if (internalKeyComparator.compare(files.get(mid).getLargest(), targetKey) < 0) {
                // Key at "mid.largest" is < "target".  Therefore all
                // files at or before "mid" are uninteresting.
                left = mid + 1;
            }
            else {
                // Key at "mid.largest" is >= "target".  Therefore all files
                // after "mid" are uninteresting.
                right = mid;
            }
        }
        return right;
    }

    public void addFile(FileMetaData fileMetaData)
    {
        // todo remove mutation
        files.add(fileMetaData);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Level");
        sb.append("{levelNumber=").append(levelNumber);
        sb.append(", files=").append(files);
        sb.append('}');
        return sb.toString();
    }
}