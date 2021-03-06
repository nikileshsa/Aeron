/*
 * Copyright 2014-2017 Real Logic Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.aeron.archiver;

import io.aeron.*;
import org.agrona.*;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Consumes an {@link Image} and archives data into file using {@link ArchiveStreamWriter}.
 */
class ArchivingSession implements ArchiveConductor.Session
{
    private enum State
    {
        INIT, ARCHIVING, CLOSING, DONE
    }

    private int streamInstanceId = ArchiveIndex.NULL_STREAM_INDEX;
    private final ArchiverProtocolProxy proxy;
    private final Image image;
    private final ArchiveIndex index;
    private final ArchiveStreamWriter.Builder builder;

    private ArchiveStreamWriter writer;

    private State state = State.INIT;

    ArchivingSession(
        final ArchiverProtocolProxy proxy,
        final ArchiveIndex index,
        final Image image,
        final ArchiveStreamWriter.Builder builder)
    {
        this.proxy = proxy;
        this.image = image;
        this.index = index;
        this.builder = builder;
    }

    public void abort()
    {
        this.state = State.CLOSING;
    }

    public int doWork()
    {
        int workDone = 0;

        if (state == State.INIT)
        {
            workDone += init();
        }

        if (state == State.ARCHIVING)
        {
            workDone += archive();
        }

        if (state == State.CLOSING)
        {
            workDone += close();
        }

        return workDone;
    }

    private int init()
    {
        final Subscription subscription = image.subscription();
        final int streamId = subscription.streamId();
        final String channel = subscription.channel();
        final int sessionId = image.sessionId();
        final String source = image.sourceIdentity();
        final int termBufferLength = image.termBufferLength();

        final int imageInitialTermId = image.initialTermId();


        ArchiveStreamWriter writer = null;
        try
        {
            streamInstanceId = index.addNewStreamInstance(
                source,
                sessionId,
                channel,
                streamId,
                termBufferLength,
                imageInitialTermId,
                this,
                builder.archiveFileSize());

            proxy.notifyArchiveStarted(
                streamInstanceId,
                source,
                sessionId,
                channel,
                streamId);

            writer = builder
                .streamInstanceId(streamInstanceId)
                .termBufferLength(termBufferLength)
                .imageInitialTermId(imageInitialTermId)
                .source(source)
                .sessionId(sessionId)
                .channel(channel)
                .streamId(streamId)
                .build();
        }
        catch (final Exception ex)
        {
            close();
            LangUtil.rethrowUnchecked(ex);
        }

        this.writer = writer;
        this.state = State.ARCHIVING;
        return 1;
    }

    int streamInstanceId()
    {
        return streamInstanceId;
    }

    private int close()
    {
        try
        {
            if (writer != null)
            {
                writer.stop();
                index.updateIndexFromMeta(streamInstanceId, writer.metaDataBuffer());
            }
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }
        finally
        {
            CloseHelper.quietClose(writer);
            proxy.notifyArchiveStopped(streamInstanceId);
            this.state = State.DONE;
        }

        return 1;
    }

    private int archive()
    {
        int workCount = 1;
        try
        {
            // TODO: add CRC as option, per fragment, use session id to store CRC
            workCount = image.rawPoll(writer, writer.archiveFileSize());
            if (workCount != 0)
            {
                proxy.notifyArchiveProgress(
                    writer.streamInstanceId(),
                    writer.initialTermId(),
                    writer.initialTermOffset(),
                    writer.lastTermId(),
                    writer.lastTermOffset());
            }

            if (image.isClosed())
            {
                state = State.CLOSING;
            }
        }
        catch (final Exception ex)
        {
            state = State.CLOSING;
            LangUtil.rethrowUnchecked(ex);
        }

        return workCount;
    }

    public boolean isDone()
    {
        return state == State.DONE;
    }

    public void remove(final ArchiveConductor conductor)
    {
        index.removeArchivingSession(streamInstanceId);
    }

    ByteBuffer metaDataBuffer()
    {
        return writer.metaDataBuffer();
    }
}
