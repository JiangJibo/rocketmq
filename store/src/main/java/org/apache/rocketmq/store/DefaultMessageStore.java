/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import org.apache.rocketmq.common.*;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.common.running.RunningStats;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.config.MessageStoreConfig;
import org.apache.rocketmq.store.config.StorePathConfigHelper;
import org.apache.rocketmq.store.ha.HAService;
import org.apache.rocketmq.store.index.IndexService;
import org.apache.rocketmq.store.index.QueryOffsetResult;
import org.apache.rocketmq.store.schedule.ScheduleMessageService;
import org.apache.rocketmq.store.stats.BrokerStatsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.rocketmq.store.config.BrokerRole.SLAVE;

public class DefaultMessageStore implements MessageStore {

    private static final Logger log = LoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);

    /**
     * 消息过滤器
     */
    private final MessageFilter messageFilter = new DefaultMessageFilter();
    /**
     * MessageStore配置
     */
    private final MessageStoreConfig messageStoreConfig;
    /**
     * CommitLog
     */
    private final CommitLog commitLog;
    /**
     * 消费队列集合
     */
    private final ConcurrentHashMap<String/* topic */, ConcurrentHashMap<Integer/* queueId */, ConsumeQueue>> consumeQueueTable;
    /**
     * flush 消费队列线程服务
     */
    private final FlushConsumeQueueService flushConsumeQueueService;

    private final CleanCommitLogService cleanCommitLogService;

    private final CleanConsumeQueueService cleanConsumeQueueService;

    private final IndexService indexService;

    private final AllocateMappedFileService allocateMappedFileService;

    /**
     * 重放消息线程服务
     */
    @SuppressWarnings("SpellCheckingInspection")
    private final ReputMessageService reputMessageService;

    private final HAService haService;

    private final ScheduleMessageService scheduleMessageService;

    private final StoreStatsService storeStatsService;

    private final TransientStorePool transientStorePool;

    private final RunningFlags runningFlags = new RunningFlags();
    private final SystemClock systemClock = new SystemClock();

    private final ScheduledExecutorService scheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl("StoreScheduledThread"));
    private final BrokerStatsManager brokerStatsManager;
    private final MessageArrivingListener messageArrivingListener;
    private final BrokerConfig brokerConfig;

    private volatile boolean shutdown = true;

    private StoreCheckpoint storeCheckpoint;

    private AtomicLong printTimes = new AtomicLong(0);

    public DefaultMessageStore(final MessageStoreConfig messageStoreConfig, final BrokerStatsManager brokerStatsManager,
                               final MessageArrivingListener messageArrivingListener, final BrokerConfig brokerConfig) throws IOException {
        this.messageArrivingListener = messageArrivingListener;
        this.brokerConfig = brokerConfig;
        this.messageStoreConfig = messageStoreConfig;
        this.brokerStatsManager = brokerStatsManager;
        this.allocateMappedFileService = new AllocateMappedFileService(this);
        this.commitLog = new CommitLog(this);
        this.consumeQueueTable = new ConcurrentHashMap<>(32);

        this.flushConsumeQueueService = new FlushConsumeQueueService();
        this.cleanCommitLogService = new CleanCommitLogService();
        this.cleanConsumeQueueService = new CleanConsumeQueueService();
        this.storeStatsService = new StoreStatsService();
        this.indexService = new IndexService(this);
        this.haService = new HAService(this);

        this.reputMessageService = new ReputMessageService();

        this.scheduleMessageService = new ScheduleMessageService(this);

        this.transientStorePool = new TransientStorePool(messageStoreConfig);

        if (messageStoreConfig.isTransientStorePoolEnable()) {
            this.transientStorePool.init();
        }

        this.allocateMappedFileService.start();

        this.indexService.start();
    }

    public void truncateDirtyLogicFiles(long phyOffset) {
        ConcurrentHashMap<String, ConcurrentHashMap<Integer, ConsumeQueue>> tables = DefaultMessageStore.this.consumeQueueTable;

        for (ConcurrentHashMap<Integer, ConsumeQueue> maps : tables.values()) {
            for (ConsumeQueue logic : maps.values()) {
                logic.truncateDirtyLogicFiles(phyOffset);
            }
        }
    }

    /**
     * @throws IOException
     */
    public boolean load() {
        boolean result = true;

        try {
            boolean lastExitOK = !this.isTempFileExist();
            log.info("last shutdown {}", lastExitOK ? "normally" : "abnormally");

            if (null != scheduleMessageService) {   //解析延迟级别,加载delayOffset.json
                result = result && this.scheduleMessageService.load();
            }

            // load Commit Log
            result = result && this.commitLog.load();

            // load Consume Queue
            result = result && this.loadConsumeQueue(); // TODO 待读

            if (result) {
                this.storeCheckpoint =
                    new StoreCheckpoint(StorePathConfigHelper.getStoreCheckpoint(this.messageStoreConfig.getStorePathRootDir()));

                this.indexService.load(lastExitOK);

                this.recover(lastExitOK);

                log.info("load over, and the max phy offset = {}", this.getMaxPhyOffset());
            }
        } catch (Exception e) {
            log.error("load exception", e);
            result = false;
        }

        if (!result) {
            this.allocateMappedFileService.shutdown();
        }

        return result;
    }

    /**
     * @throws Exception
     */
    public void start() throws Exception {
        this.flushConsumeQueueService.start();              //启动ConsumeQueue Flush线程
        this.commitLog.start();                             //启动Flush线程,若开启缓冲池则也启动Commit线程
        this.storeStatsService.start();                     //启动TPS及状态新打印线程

        if (this.scheduleMessageService != null && SLAVE != messageStoreConfig.getBrokerRole()) {
            this.scheduleMessageService.start();            //Master启动延时任务投递线程,同时每隔10S持久化每隔延时队列的投递进度
        }

        if (this.getMessageStoreConfig().isDuplicationEnable()) {
            this.reputMessageService.setReputFromOffset(this.commitLog.getConfirmOffset());
        } else {                                            //指定ReputMessageService从CommitLog当前最大进度开始
            this.reputMessageService.setReputFromOffset(this.commitLog.getMaxOffset());
        }
        this.reputMessageService.start();                   //启动CommitLog内消息的Reput线程,每隔1ms执行一次

        this.haService.start();                             //启动高可用服务

        this.createTempFile();
        this.addScheduleTask();                             //启动清理失效的CommitLog,ConsumeQueue,IndexFile线程
        this.shutdown = false;
    }

    /**

     */
    public void shutdown() {
        if (!this.shutdown) {
            this.shutdown = true;

            this.scheduledExecutorService.shutdown();

            try {

                Thread.sleep(1000 * 3);
            } catch (InterruptedException e) {
                log.error("shutdown Exception, ", e);
            }

            if (this.scheduleMessageService != null) {
                this.scheduleMessageService.shutdown();
            }

            this.haService.shutdown();

            this.storeStatsService.shutdown();
            this.indexService.shutdown();
            this.commitLog.shutdown();
            this.reputMessageService.shutdown();
            this.flushConsumeQueueService.shutdown();
            this.allocateMappedFileService.shutdown();
            this.storeCheckpoint.flush();
            this.storeCheckpoint.shutdown();

            if (this.runningFlags.isWriteable()) {
                this.deleteFile(StorePathConfigHelper.getAbortFile(this.messageStoreConfig.getStorePathRootDir()));
            } else {
                log.warn("the store may be wrong, so shutdown abnormally, and keep abort file.");
            }
        }

        this.transientStorePool.destroy();
    }

    public void destroy() {
        this.destroyLogics();
        this.commitLog.destroy();
        this.indexService.destroy();
        this.deleteFile(StorePathConfigHelper.getAbortFile(this.messageStoreConfig.getStorePathRootDir()));
        this.deleteFile(StorePathConfigHelper.getStoreCheckpoint(this.messageStoreConfig.getStorePathRootDir()));
    }

    public void destroyLogics() {
        for (ConcurrentHashMap<Integer, ConsumeQueue> maps : this.consumeQueueTable.values()) {
            for (ConsumeQueue logic : maps.values()) {
                logic.destroy();
            }
        }
    }

    @Override
    public PutMessageResult putMessage(MessageExtBrokerInner msg) {
        if (this.shutdown) {
            log.warn("message store has shutdown, so putMessage is forbidden");
            return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null);
        }

        // 从节点不允许写入
        if (BrokerRole.SLAVE == this.messageStoreConfig.getBrokerRole()) {
            long value = this.printTimes.getAndIncrement();
            if ((value % 50000) == 0) {
                log.warn("message store is slave mode, so putMessage is forbidden ");
            }

            return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null);
        }

        // store是否允许写入
        if (!this.runningFlags.isWriteable()) {
            long value = this.printTimes.getAndIncrement();
            if ((value % 50000) == 0) {
                log.warn("message store is not writeable, so putMessage is forbidden " + this.runningFlags.getFlagBits());
            }

            return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null);
        } else {
            this.printTimes.set(0);
        }

        // 消息过长
        if (msg.getTopic().length() > Byte.MAX_VALUE) {
            log.warn("putMessage message topic length too long " + msg.getTopic().length());
            return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, null);
        }

        // 消息附加属性过长
        if (msg.getPropertiesString() != null && msg.getPropertiesString().length() > Short.MAX_VALUE) {
            log.warn("putMessage message properties length too long " + msg.getPropertiesString().length());
            return new PutMessageResult(PutMessageStatus.PROPERTIES_SIZE_EXCEEDED, null);
        }

        if (this.isOSPageCacheBusy()) {
            return new PutMessageResult(PutMessageStatus.OS_PAGECACHE_BUSY, null);
        }

        long beginTime = this.getSystemClock().now();
        // 添加消息到commitLog
        PutMessageResult result = this.commitLog.putMessage(msg);

        long eclipseTime = this.getSystemClock().now() - beginTime;
        if (eclipseTime > 500) {
            log.warn("putMessage not in lock eclipse time(ms)={}, bodyLength={}", eclipseTime, msg.getBody().length);
        }
        this.storeStatsService.setPutMessageEntireTimeMax(eclipseTime);

        if (null == result || !result.isOk()) {
            this.storeStatsService.getPutMessageFailedTimes().incrementAndGet();
        }

        return result;
    }

    @Override
    public boolean isOSPageCacheBusy() {
        long begin = this.getCommitLog().getBeginTimeInLock();
        long diff = this.systemClock.now() - begin;

        if (diff < 10000000 //
            && diff > this.messageStoreConfig.getOsPageCacheBusyTimeOutMills()) {
            return true;
        }

        return false;
    }

    @Override
    public long lockTimeMills() {
        return this.commitLog.lockTimeMills();
    }

    public SystemClock getSystemClock() {
        return systemClock;
    }

    public CommitLog getCommitLog() {
        return commitLog;
    }

    /**
     * 通过topic,queueId,offset从ConsumeQueue里获取新加入消息的消费信息,然后从CommitLog里提取消息
     * 从ConsumeQueue里提取Message时会根据tagsCode过滤不符合的Message
     * 提取的总数(32)条消息都是符合tag的
     *
     * @param group            消费分组
     * @param topic            主题
     * @param queueId          队列编号
     * @param offset           ConsumeQueue Offset
     * @param maxMsgNums       消息数量  32
     * @param subscriptionData 订阅信息
     * @return 消息结果
     */
    @Override
    public GetMessageResult getMessage(final String group, final String topic, final int queueId, final long offset, final int maxMsgNums,
                                       final SubscriptionData subscriptionData) {
        // 是否关闭
        if (this.shutdown) {
            log.warn("message store has shutdown, so getMessage is forbidden");
            return null;
        }
        // 是否可读
        if (!this.runningFlags.isReadable()) {
            log.warn("message store is not readable, so getMessage is forbidden " + this.runningFlags.getFlagBits());
            return null;
        }

        long beginTime = this.getSystemClock().now();

        GetMessageStatus status = GetMessageStatus.NO_MESSAGE_IN_QUEUE;
        long nextBeginOffset = offset;
        long minOffset = 0;
        long maxOffset = 0;

        GetMessageResult getResult = new GetMessageResult();

        final long maxOffsetPy = this.commitLog.getMaxOffset();

        // 获取消费队列
        ConsumeQueue consumeQueue = findConsumeQueue(topic, queueId);
        if (consumeQueue != null) {
            minOffset = consumeQueue.getMinOffsetInQueue(); // 消费队列 最小消息序号
            maxOffset = consumeQueue.getMaxOffsetInQueue(); // 消费队列 最大消息序号

            // 校验消费的Offset是否准确,若不准确是否修正,修正条件：(当前主节点) or (当前从节点开启校验offset开关),不正确时仅修正不消费
            if (maxOffset == 0) { // 消费队列无消息
                status = GetMessageStatus.NO_MESSAGE_IN_QUEUE;
                nextBeginOffset = nextOffsetCorrection(offset, 0);
            } else if (offset < minOffset) { // 查询offset 太小
                status = GetMessageStatus.OFFSET_TOO_SMALL;
                nextBeginOffset = nextOffsetCorrection(offset, minOffset);
            } else if (offset == maxOffset) { // 查询offset 超过 消费队列 一个位置
                status = GetMessageStatus.OFFSET_OVERFLOW_ONE;
                nextBeginOffset = nextOffsetCorrection(offset, offset);
            } else if (offset > maxOffset) { // 查询offset 超过 消费队列 太多(大于一个位置)
                status = GetMessageStatus.OFFSET_OVERFLOW_BADLY;
                if (0 == minOffset) { // TODO blog 这里是？？为啥0 == minOffset做了特殊判断
                    nextBeginOffset = nextOffsetCorrection(offset, minOffset);
                } else {
                    nextBeginOffset = nextOffsetCorrection(offset, maxOffset);
                }
            } else {   //Offset正确,无需修正
                // 获得 映射Buffer结果(MappedFile)
                SelectMappedBufferResult bufferConsumeQueue = consumeQueue.getIndexBuffer(offset);
                if (bufferConsumeQueue != null) {
                    try {
                        status = GetMessageStatus.NO_MATCHED_MESSAGE;

                        long nextPhyFileStartOffset = Long.MIN_VALUE; // commitLog下一个文件(MappedFile)对应的开始offset。
                        long maxPhyOffsetPulling = 0; // 消息物理位置拉取到的最大offset

                        int i = 0;
                        final int maxFilterMessageCount = 16000;  //每次拉取最多16000字节,也就是800个消息的ConsumeQueue
                        final boolean diskFallRecorded = this.messageStoreConfig.isDiskFallRecorded();
                        // 循环获取 消息位置信息
                        for (; i < bufferConsumeQueue.getSize() && i < maxFilterMessageCount; i += ConsumeQueue.CQ_STORE_UNIT_SIZE) {
                            long offsetPy = bufferConsumeQueue.getByteBuffer().getLong(); // 消息物理位置offset
                            int sizePy = bufferConsumeQueue.getByteBuffer().getInt(); // 消息长度
                            long tagsCode = bufferConsumeQueue.getByteBuffer().getLong(); // 消息tagsCode
                            // 设置消息物理位置拉取到的最大offset
                            maxPhyOffsetPulling = offsetPy;
                            // 当 offsetPy 小于 nextPhyFileStartOffset 时，意味着对应的 Message 已经移除，所以直接continue，直到可读取的Message。
                            if (nextPhyFileStartOffset != Long.MIN_VALUE) {
                                if (offsetPy < nextPhyFileStartOffset) {
                                    continue;
                                }
                            }
                            // 校验 commitLog 是否需要硬盘，无法全部放在内存
                            boolean isInDisk = checkInDiskByCommitOffset(offsetPy, maxOffsetPy);
                            // 是否已经获得足够消息,若加上当前解析的这条消息超出限制,则中断此条信息的解析
                            if (this.isTheBatchFull(sizePy, maxMsgNums, getResult.getBufferTotalSize(), getResult.getMessageCount(), isInDisk)) {
                                break;
                            }
                            // 判断消息是否在订阅的tag里
                            if (this.messageFilter.isMessageMatched(subscriptionData, tagsCode)) {
                                // 从commitLog获取对应消息ByteBuffer
                                SelectMappedBufferResult selectResult = this.commitLog.getMessage(offsetPy, sizePy);
                                if (selectResult != null) {
                                    this.storeStatsService.getGetMessageTransferedMsgCount().incrementAndGet();
                                    getResult.addMessage(selectResult);
                                    status = GetMessageStatus.FOUND;
                                    nextPhyFileStartOffset = Long.MIN_VALUE;
                                } else {
                                    // 从commitLog无法读取到消息，说明该消息对应的文件（MappedFile）已经删除，计算下一个MappedFile的起始位置
                                    if (getResult.getBufferTotalSize() == 0) {
                                        status = GetMessageStatus.MESSAGE_WAS_REMOVING;
                                    }
                                    nextPhyFileStartOffset = this.commitLog.rollNextFile(offsetPy);
                                }
                            } else {
                                if (getResult.getBufferTotalSize() == 0) {
                                    status = GetMessageStatus.NO_MATCHED_MESSAGE;
                                }

                                if (log.isDebugEnabled()) {
                                    log.debug("message type not matched, client: " + subscriptionData + " server: " + tagsCode);
                                }
                            }
                        }
                        // 统计剩余可拉取消息字节数
                        if (diskFallRecorded) {
                            long fallBehind = maxOffsetPy/*CommitLog Offset*/ - maxPhyOffsetPulling/*消费到CommitLog的Offset*/;
                            brokerStatsManager.recordDiskFallBehindSize(group, topic, queueId, fallBehind);
                        }
                        // 计算下次拉取消息的消费队列的序号
                        nextBeginOffset = offset + (i / ConsumeQueue.CQ_STORE_UNIT_SIZE);
                        // 剩余待拉取消息的字节数
                        long diff = maxOffsetPy - maxPhyOffsetPulling;
                        // 配置的内存中可用来存储待拉取消息的大小,默认为总内存的40%
                        long memory = (long)(StoreUtil.TOTAL_PHYSICAL_MEMORY_SIZE * (this.messageStoreConfig.getAccessMessageInMemoryMaxRatio() / 100.0));
                        // 待拉取的消息已经超过了总内存的40%,也就是说已经积累了大量的消息未消费,
                        // 有很多消息已经存储到CommitLog文件中,此时消息可能要从文件中读取,性能很低
                        // 从Master拉取的速度太慢了,可能是IO异常或者IO压力很大,建议从Slave拉取
                        getResult.setSuggestPullingFromSlave(diff > memory);
                    } finally {
                        bufferConsumeQueue.release();
                    }
                } else {
                    status = GetMessageStatus.OFFSET_FOUND_NULL;
                    nextBeginOffset = nextOffsetCorrection(offset, consumeQueue.rollNextFile(offset));
                    log.warn("consumer request topic: " + topic + "offset: " + offset + " minOffset: " + minOffset + " maxOffset: "
                        + maxOffset + ", but access logic queue failed.");
                }
            }
        } else {
            status = GetMessageStatus.NO_MATCHED_LOGIC_QUEUE;
            nextBeginOffset = nextOffsetCorrection(offset, 0);
        }
        // 统计
        if (GetMessageStatus.FOUND == status) {
            this.storeStatsService.getGetMessageTimesTotalFound().incrementAndGet();
        } else {
            this.storeStatsService.getGetMessageTimesTotalMiss().incrementAndGet();
        }
        long eclipseTime = this.getSystemClock().now() - beginTime;
        this.storeStatsService.setGetMessageEntireTimeMax(eclipseTime);
        // 设置返回结果
        getResult.setStatus(status);
        getResult.setNextBeginOffset(nextBeginOffset);
        getResult.setMaxOffset(maxOffset);
        getResult.setMinOffset(minOffset);
        return getResult;
    }

    /**

     */
    public long getMaxOffsetInQuque(String topic, int queueId) {
        ConsumeQueue logic = this.findConsumeQueue(topic, queueId);
        if (logic != null) {
            long offset = logic.getMaxOffsetInQueue();
            return offset;
        }

        return 0;
    }

    /**

     */
    public long getMinOffsetInQuque(String topic, int queueId) {
        ConsumeQueue logic = this.findConsumeQueue(topic, queueId);
        if (logic != null) {
            return logic.getMinOffsetInQueue();
        }

        return -1;
    }

    @Override
    public long getCommitLogOffsetInQueue(String topic, int queueId, long cqOffset) {
        ConsumeQueue consumeQueue = findConsumeQueue(topic, queueId);
        if (consumeQueue != null) {
            SelectMappedBufferResult bufferConsumeQueue = consumeQueue.getIndexBuffer(cqOffset);
            if (bufferConsumeQueue != null) {
                try {
                    long offsetPy = bufferConsumeQueue.getByteBuffer().getLong();
                    return offsetPy;
                } finally {
                    bufferConsumeQueue.release();
                }
            }
        }

        return 0;
    }

    public long getOffsetInQueueByTime(String topic, int queueId, long timestamp) {
        ConsumeQueue logic = this.findConsumeQueue(topic, queueId);
        if (logic != null) {
            return logic.getOffsetInQueueByTime(timestamp);
        }

        return 0;
    }

    public MessageExt lookMessageByOffset(long commitLogOffset) {
        SelectMappedBufferResult sbr = this.commitLog.getMessage(commitLogOffset, 4);
        if (null != sbr) {
            try {
                // 1 TOTALSIZE
                int size = sbr.getByteBuffer().getInt();
                return lookMessageByOffset(commitLogOffset, size);
            } finally {
                sbr.release();
            }
        }

        return null;
    }

    @Override
    public SelectMappedBufferResult selectOneMessageByOffset(long commitLogOffset) {
        SelectMappedBufferResult sbr = this.commitLog.getMessage(commitLogOffset, 4);
        if (null != sbr) {
            try {
                // 1 TOTALSIZE
                int size = sbr.getByteBuffer().getInt();
                return this.commitLog.getMessage(commitLogOffset, size);
            } finally {
                sbr.release();
            }
        }

        return null;
    }

    @Override
    public SelectMappedBufferResult selectOneMessageByOffset(long commitLogOffset, int msgSize) {
        return this.commitLog.getMessage(commitLogOffset, msgSize);
    }

    public String getRunningDataInfo() {
        return this.storeStatsService.toString();
    }

    @Override
    public HashMap<String, String> getRuntimeInfo() {
        HashMap<String, String> result = this.storeStatsService.getRuntimeInfo();

        {
            String storePathPhysic = DefaultMessageStore.this.getMessageStoreConfig().getStorePathCommitLog();
            double physicRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathPhysic);
            result.put(RunningStats.commitLogDiskRatio.name(), String.valueOf(physicRatio));

        }

        {

            String storePathLogics = StorePathConfigHelper.getStorePathConsumeQueue(this.messageStoreConfig.getStorePathRootDir());
            double logicsRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathLogics);
            result.put(RunningStats.consumeQueueDiskRatio.name(), String.valueOf(logicsRatio));
        }

        {
            if (this.scheduleMessageService != null) {
                this.scheduleMessageService.buildRunningStats(result);
            }
        }

        result.put(RunningStats.commitLogMinOffset.name(), String.valueOf(DefaultMessageStore.this.getMinPhyOffset()));
        result.put(RunningStats.commitLogMaxOffset.name(), String.valueOf(DefaultMessageStore.this.getMaxPhyOffset()));

        return result;
    }

    @Override
    public long getMaxPhyOffset() {
        return this.commitLog.getMaxOffset();
    }

    @Override
    public long getMinPhyOffset() {
        return this.commitLog.getMinOffset();
    }

    @Override
    public long getEarliestMessageTime(String topic, int queueId) {
        ConsumeQueue logicQueue = this.findConsumeQueue(topic, queueId);
        if (logicQueue != null) {
            long minLogicOffset = logicQueue.getMinLogicOffset();

            SelectMappedBufferResult result = logicQueue.getIndexBuffer(minLogicOffset / ConsumeQueue.CQ_STORE_UNIT_SIZE);
            if (result != null) {
                try {
                    final long phyOffset = result.getByteBuffer().getLong();
                    final int size = result.getByteBuffer().getInt();
                    long storeTime = this.getCommitLog().pickupStoreTimestamp(phyOffset, size);
                    return storeTime;
                } catch (Exception e) {
                } finally {
                    result.release();
                }
            }
        }

        return -1;
    }

    @Override
    public long getEarliestMessageTime() {
        final long minPhyOffset = this.getMinPhyOffset();
        final int size = this.messageStoreConfig.getMaxMessageSize() * 2;
        return this.getCommitLog().pickupStoreTimestamp(minPhyOffset, size);
    }

    @Override
    public long getMessageStoreTimeStamp(String topic, int queueId, long offset) {
        ConsumeQueue logicQueue = this.findConsumeQueue(topic, queueId);
        if (logicQueue != null) {
            SelectMappedBufferResult result = logicQueue.getIndexBuffer(offset);
            if (result != null) {
                try {
                    final long phyOffset = result.getByteBuffer().getLong();
                    final int size = result.getByteBuffer().getInt();
                    long storeTime = this.getCommitLog().pickupStoreTimestamp(phyOffset, size);
                    return storeTime;
                } catch (Exception ignored) {
                } finally {
                    result.release();
                }
            }
        }

        return -1;
    }

    @Override
    public long getMessageTotalInQueue(String topic, int queueId) {
        ConsumeQueue logicQueue = this.findConsumeQueue(topic, queueId);
        if (logicQueue != null) {
            return logicQueue.getMessageTotalInQueue();
        }

        return -1;
    }

    @Override
    public SelectMappedBufferResult getCommitLogData(final long offset) {
        if (this.shutdown) {
            log.warn("message store has shutdown, so getPhyQueueData is forbidden");
            return null;
        }

        return this.commitLog.getData(offset);
    }

    /**
     * commitLog添加数据
     * ！该方法主要在Master与Slave同步数据时调用
     *
     * @param startOffset 开始物理位置
     * @param data        数据
     * @return 是否成功
     */
    @Override
    public boolean appendToCommitLog(long startOffset, byte[] data) {
        if (this.shutdown) {
            log.warn("message store has shutdown, so appendToPhyQueue is forbidden");
            return false;
        }

        boolean result = this.commitLog.appendData(startOffset, data);
        if (result) {
            this.reputMessageService.wakeup();
        } else {
            log.error("appendToPhyQueue failed " + startOffset + " " + data.length);
        }

        return result;
    }

    @Override
    public void excuteDeleteFilesManualy() {
        this.cleanCommitLogService.excuteDeleteFilesManualy();
    }

    /**
     * 根据条件查询消息
     *
     * @param topic
     * @param key
     * @param maxNum
     * @param begin
     * @param end
     * @return
     */
    @Override
    public QueryMessageResult queryMessage(String topic, String key, int maxNum, long begin, long end) {
        QueryMessageResult queryMessageResult = new QueryMessageResult();

        long lastQueryMsgTime = end;

        for (int i = 0; i < 3; i++) {
            QueryOffsetResult queryOffsetResult = this.indexService.queryOffset(topic, key, maxNum, begin, lastQueryMsgTime);
            if (queryOffsetResult.getPhyOffsets().isEmpty()) {
                break;
            }

            Collections.sort(queryOffsetResult.getPhyOffsets());

            queryMessageResult.setIndexLastUpdatePhyoffset(queryOffsetResult.getIndexLastUpdatePhyoffset());
            queryMessageResult.setIndexLastUpdateTimestamp(queryOffsetResult.getIndexLastUpdateTimestamp());

            for (int m = 0; m < queryOffsetResult.getPhyOffsets().size(); m++) {
                long offset = queryOffsetResult.getPhyOffsets().get(m);

                try {

                    boolean match = true;
                    MessageExt msg = this.lookMessageByOffset(offset);
                    if (0 == m) {
                        lastQueryMsgTime = msg.getStoreTimestamp();
                    }

                    //                    String[] keyArray = msg.getKeys().split(MessageConst.KEY_SEPARATOR);
                    //                    if (topic.equals(msg.getTopic())) {
                    //                        for (String k : keyArray) {
                    //                            if (k.equals(key)) {
                    //                                match = true;
                    //                                break;
                    //                            }
                    //                        }
                    //                    }

                    if (match) {
                        SelectMappedBufferResult result = this.commitLog.getData(offset, false);
                        if (result != null) {
                            int size = result.getByteBuffer().getInt(0);
                            result.getByteBuffer().limit(size);
                            result.setSize(size);
                            queryMessageResult.addMessage(result);
                        }
                    } else {
                        log.warn("queryMessage hash duplicate, {} {}", topic, key);
                    }
                } catch (Exception e) {
                    log.error("queryMessage exception", e);
                }
            }

            if (queryMessageResult.getBufferTotalSize() > 0) {
                break;
            }

            if (lastQueryMsgTime < begin) {
                break;
            }
        }

        return queryMessageResult;
    }

    @Override
    public void updateHaMasterAddress(String newAddr) {
        this.haService.updateMasterAddress(newAddr);
    }

    @Override
    public long slaveFallBehindMuch() {
        return this.commitLog.getMaxOffset() - this.haService.getPush2SlaveMaxOffset().get();
    }

    @Override
    public long now() {
        return this.systemClock.now();
    }

    @Override
    public int cleanUnusedTopic(Set<String> topics) {
        Iterator<Entry<String, ConcurrentHashMap<Integer, ConsumeQueue>>> it = this.consumeQueueTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConcurrentHashMap<Integer, ConsumeQueue>> next = it.next();
            String topic = next.getKey();

            if (!topics.contains(topic) && !topic.equals(ScheduleMessageService.SCHEDULE_TOPIC)) {
                ConcurrentHashMap<Integer, ConsumeQueue> queueTable = next.getValue();
                for (ConsumeQueue cq : queueTable.values()) {
                    cq.destroy();
                    log.info("cleanUnusedTopic: {} {} ConsumeQueue cleaned", //
                        cq.getTopic(), //
                        cq.getQueueId() //
                    );

                    this.commitLog.removeQueueFromTopicQueueTable(cq.getTopic(), cq.getQueueId());
                }
                it.remove();

                log.info("cleanUnusedTopic: {},topic destroyed", topic);
            }
        }

        return 0;
    }

    public void cleanExpiredConsumerQueue() {
        long minCommitLogOffset = this.commitLog.getMinOffset();

        Iterator<Entry<String, ConcurrentHashMap<Integer, ConsumeQueue>>> it = this.consumeQueueTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConcurrentHashMap<Integer, ConsumeQueue>> next = it.next();
            String topic = next.getKey();
            if (!topic.equals(ScheduleMessageService.SCHEDULE_TOPIC)) {
                ConcurrentHashMap<Integer, ConsumeQueue> queueTable = next.getValue();
                Iterator<Entry<Integer, ConsumeQueue>> itQT = queueTable.entrySet().iterator();
                while (itQT.hasNext()) {
                    Entry<Integer, ConsumeQueue> nextQT = itQT.next();
                    long maxCLOffsetInConsumeQueue = nextQT.getValue().getLastOffset();

                    if (maxCLOffsetInConsumeQueue == -1) {
                        log.warn("maybe ConsumeQueue was created just now. topic={} queueId={} maxPhysicOffset={} minLogicOffset={}.", //
                            nextQT.getValue().getTopic(), //
                            nextQT.getValue().getQueueId(), //
                            nextQT.getValue().getMaxPhysicOffset(), //
                            nextQT.getValue().getMinLogicOffset());
                    } else if (maxCLOffsetInConsumeQueue < minCommitLogOffset) {
                        log.info(
                            "cleanExpiredConsumerQueue: {} {} consumer queue destroyed, minCommitLogOffset: {} maxCLOffsetInConsumeQueue: {}", //
                            topic, //
                            nextQT.getKey(), //
                            minCommitLogOffset, //
                            maxCLOffsetInConsumeQueue);

                        DefaultMessageStore.this.commitLog.removeQueueFromTopicQueueTable(nextQT.getValue().getTopic(),
                            nextQT.getValue().getQueueId());

                        nextQT.getValue().destroy();
                        itQT.remove();
                    }
                }

                if (queueTable.isEmpty()) {
                    log.info("cleanExpiredConsumerQueue: {},topic destroyed", topic);
                    it.remove();
                }
            }
        }
    }

    public Map<String, Long> getMessageIds(final String topic, final int queueId, long minOffset, long maxOffset, SocketAddress storeHost) {
        Map<String, Long> messageIds = new HashMap<String, Long>();
        if (this.shutdown) {
            return messageIds;
        }

        ConsumeQueue consumeQueue = findConsumeQueue(topic, queueId);
        if (consumeQueue != null) {
            minOffset = Math.max(minOffset, consumeQueue.getMinOffsetInQueue());
            maxOffset = Math.min(maxOffset, consumeQueue.getMaxOffsetInQueue());

            if (maxOffset == 0) {
                return messageIds;
            }

            long nextOffset = minOffset;
            while (nextOffset < maxOffset) {
                SelectMappedBufferResult bufferConsumeQueue = consumeQueue.getIndexBuffer(nextOffset);
                if (bufferConsumeQueue != null) {
                    try {
                        int i = 0;
                        for (; i < bufferConsumeQueue.getSize(); i += ConsumeQueue.CQ_STORE_UNIT_SIZE) {
                            long offsetPy = bufferConsumeQueue.getByteBuffer().getLong();
                            final ByteBuffer msgIdMemory = ByteBuffer.allocate(MessageDecoder.MSG_ID_LENGTH);
                            String msgId =
                                MessageDecoder.createMessageId(msgIdMemory, MessageExt.socketAddress2ByteBuffer(storeHost), offsetPy);
                            messageIds.put(msgId, nextOffset++);
                            if (nextOffset > maxOffset) {
                                return messageIds;
                            }
                        }
                    } finally {

                        bufferConsumeQueue.release();
                    }
                } else {
                    return messageIds;
                }
            }
        }
        return messageIds;
    }

    @Override
    public boolean checkInDiskByConsumeOffset(final String topic, final int queueId, long consumeOffset) {

        final long maxOffsetPy = this.commitLog.getMaxOffset();

        ConsumeQueue consumeQueue = findConsumeQueue(topic, queueId);
        if (consumeQueue != null) {
            SelectMappedBufferResult bufferConsumeQueue = consumeQueue.getIndexBuffer(consumeOffset);
            if (bufferConsumeQueue != null) {
                try {
                    for (int i = 0; i < bufferConsumeQueue.getSize(); ) {
                        i += ConsumeQueue.CQ_STORE_UNIT_SIZE;
                        long offsetPy = bufferConsumeQueue.getByteBuffer().getLong();
                        return checkInDiskByCommitOffset(offsetPy, maxOffsetPy);
                    }
                } finally {

                    bufferConsumeQueue.release();
                }
            } else {
                return false;
            }
        }
        return false;
    }

    public long dispatchBehindBytes() {
        return this.reputMessageService.behind();
    }

    @Override
    public long flush() {
        return this.commitLog.flush();
    }

    @Override
    public boolean resetWriteOffset(long phyOffset) {
        return this.commitLog.resetOffset(phyOffset);
    }

    @Override
    public long getConfirmOffset() {
        return this.commitLog.getConfirmOffset();
    }

    @Override
    public void setConfirmOffset(long phyOffset) {
        this.commitLog.setConfirmOffset(phyOffset);
    }

    public MessageExt lookMessageByOffset(long commitLogOffset, int size) {
        SelectMappedBufferResult sbr = this.commitLog.getMessage(commitLogOffset, size);
        if (null != sbr) {
            try {
                return MessageDecoder.decode(sbr.getByteBuffer(), true, false);
            } finally {
                sbr.release();
            }
        }

        return null;
    }

    /**
     * 根据 主题 + 队列编号 获取 消费队列
     * 如果不存在，则创建一个目录../consumequeue/topic/queueId，将这个目录封装成一个MappedFileQueue后存入ConsumeQueue中,对外提供无限量空间
     * 目录内的每个消费信息文件大小为6000000B，也就是5.72MB，目录名称类似CommitLog,第一个就是000000000000，第二个为000000000000+1*fileSize=000006000000
     *
     * @param topic   主题
     * @param queueId 队列编号
     * @return 消费队列
     */
    public ConsumeQueue findConsumeQueue(String topic, int queueId) {
        // 获取 topic 对应的 所有消费队列
        ConcurrentHashMap<Integer, ConsumeQueue> map = consumeQueueTable.get(topic);
        if (null == map) {
            ConcurrentHashMap<Integer, ConsumeQueue> newMap = new ConcurrentHashMap<>(128);
            ConcurrentHashMap<Integer, ConsumeQueue> oldMap = consumeQueueTable.putIfAbsent(topic, newMap);
            if (oldMap != null) {
                map = oldMap;
            } else {
                map = newMap;
            }
        }
        // 获取 queueId 对应的 消费队列
        ConsumeQueue logic = map.get(queueId);
        if (null == logic) {
            ConsumeQueue newLogic = new ConsumeQueue(topic, queueId,
                StorePathConfigHelper.getStorePathConsumeQueue(this.messageStoreConfig.getStorePathRootDir()),  //rootDir + File.separator + "consumequeue"
                this.getMessageStoreConfig().getMapedFileSizeConsumeQueue(),   // 6000000(字节)
                this);
            ConsumeQueue oldLogic = map.putIfAbsent(queueId, newLogic);
            if (oldLogic != null) {
                logic = oldLogic;
            } else {
                logic = newLogic;
            }
        }

        return logic;
    }

    /**
     * 是否要将oldOffset的值修正为newOffset
     * 修正前提：主节点 或者 从节点开启校验offset开关
     *
     * @param oldOffset 查询时指定的起始Offset
     * @param newOffset 实际的Offset值
     * @return 修正后的队列offset
     */
    private long nextOffsetCorrection(long oldOffset, long newOffset) {
        long nextOffset = oldOffset;
        if (this.getMessageStoreConfig().getBrokerRole() != BrokerRole.SLAVE || this.getMessageStoreConfig().isOffsetCheckInSlave()) {
            nextOffset = newOffset;
        }
        return nextOffset;
    }

    /**
     * 校验 commitLog 是否需要硬盘，无法全部放在内存
     *
     * @param offsetPy    commitLog 指定offset
     * @param maxOffsetPy commitLog 最大offset
     * @return 是否需要硬盘
     */
    private boolean checkInDiskByCommitOffset(long offsetPy, long maxOffsetPy) {
        long memory = (long)(StoreUtil.TOTAL_PHYSICAL_MEMORY_SIZE * (this.messageStoreConfig.getAccessMessageInMemoryMaxRatio() / 100.0));
        return (maxOffsetPy - offsetPy) > memory;
    }

    /**
     * 判断获取消息是否已经满
     *
     * @param sizePy       字节数
     * @param maxMsgNums   最大消息数
     * @param bufferTotal  目前已经计算字节数
     * @param messageTotal 目前已经计算消息数
     * @param isInDisk     是否在硬盘中
     * @return 是否已满
     */
    private boolean isTheBatchFull(int sizePy, int maxMsgNums, int bufferTotal, int messageTotal, boolean isInDisk) {
        if (0 == bufferTotal || 0 == messageTotal) {
            return false;
        }
        // 消息数量已经满足请求数量(maxMsgNums)
        if ((messageTotal + 1) >= maxMsgNums) {
            return true;
        }
        // 根据消息存储配置的最大传输字节数、最大传输消息数是否已满
        if (isInDisk) {
            if ((bufferTotal + sizePy) > this.messageStoreConfig.getMaxTransferBytesOnMessageInDisk()) {  // >64K
                return true;
            }

            if ((messageTotal + 1) > this.messageStoreConfig.getMaxTransferCountOnMessageInDisk()) {  // >8条
                return true;
            }
        } else {
            if ((bufferTotal + sizePy) > this.messageStoreConfig.getMaxTransferBytesOnMessageInMemory()) {  // >256K
                return true;
            }

            if ((messageTotal + 1) > this.messageStoreConfig.getMaxTransferCountOnMessageInMemory()) {  // >32条
                return true;
            }
        }

        return false;
    }

    private void deleteFile(final String fileName) {
        File file = new File(fileName);
        boolean result = file.delete();
        log.info(fileName + (result ? " delete OK" : " delete Failed"));
    }

    /**
     * @throws IOException
     */
    private void createTempFile() throws IOException {
        String fileName = StorePathConfigHelper.getAbortFile(this.messageStoreConfig.getStorePathRootDir());
        File file = new File(fileName);
        MappedFile.ensureDirOK(file.getParent());
        boolean result = file.createNewFile();
        log.info(fileName + (result ? " create OK" : " already exists"));
    }

    private void addScheduleTask() {

        //启动CommitLog,ConsumeQueue过期文件删除工作
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                DefaultMessageStore.this.cleanFilesPeriodically();
            }
        }, 1000 * 60, this.messageStoreConfig.getCleanResourceInterval(), TimeUnit.MILLISECONDS);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                DefaultMessageStore.this.checkSelf();
            }
        }, 1, 10, TimeUnit.MINUTES);

        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (DefaultMessageStore.this.getMessageStoreConfig().isDebugLockEnable()) {
                    try {
                        if (DefaultMessageStore.this.commitLog.getBeginTimeInLock() != 0) {
                            long lockTime = System.currentTimeMillis() - DefaultMessageStore.this.commitLog.getBeginTimeInLock();
                            if (lockTime > 1000 && lockTime < 10000000) {

                                String stack = UtilAll.jstack();
                                final String fileName = System.getProperty("user.home") + File.separator + "debug/lock/stack-"
                                    + DefaultMessageStore.this.commitLog.getBeginTimeInLock() + "-" + lockTime;
                                MixAll.string2FileNotSafe(stack, fileName);
                            }
                        }
                    } catch (Exception e) {
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        // this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
        // @Override
        // public void run() {
        // DefaultMessageStore.this.cleanExpiredConsumerQueue();
        // }
        // }, 1, 1, TimeUnit.HOURS);
    }

    private void cleanFilesPeriodically() {
        this.cleanCommitLogService.run();
        this.cleanConsumeQueueService.run();
    }

    private void checkSelf() {
        this.commitLog.checkSelf();

        Iterator<Entry<String, ConcurrentHashMap<Integer, ConsumeQueue>>> it = this.consumeQueueTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConcurrentHashMap<Integer, ConsumeQueue>> next = it.next();
            Iterator<Entry<Integer, ConsumeQueue>> itNext = next.getValue().entrySet().iterator();
            while (itNext.hasNext()) {
                Entry<Integer, ConsumeQueue> cq = itNext.next();
                cq.getValue().checkSelf();
            }
        }
    }

    private boolean isTempFileExist() {
        String fileName = StorePathConfigHelper.getAbortFile(this.messageStoreConfig.getStorePathRootDir());
        File file = new File(fileName);
        return file.exists();
    }

    private boolean loadConsumeQueue() {
        File dirLogic = new File(StorePathConfigHelper.getStorePathConsumeQueue(this.messageStoreConfig.getStorePathRootDir()));
        File[] fileTopicList = dirLogic.listFiles();
        if (fileTopicList != null) {

            for (File fileTopic : fileTopicList) {
                String topic = fileTopic.getName();

                File[] fileQueueIdList = fileTopic.listFiles();
                if (fileQueueIdList != null) {
                    for (File fileQueueId : fileQueueIdList) {
                        int queueId;
                        try {
                            queueId = Integer.parseInt(fileQueueId.getName());
                        } catch (NumberFormatException e) {
                            continue;
                        }
                        ConsumeQueue logic = new ConsumeQueue(
                            topic,
                            queueId,
                            StorePathConfigHelper.getStorePathConsumeQueue(this.messageStoreConfig.getStorePathRootDir()),
                            this.getMessageStoreConfig().getMapedFileSizeConsumeQueue(),
                            this);
                        this.putConsumeQueue(topic, queueId, logic);
                        if (!logic.load()) {
                            return false;
                        }
                    }
                }
            }
        }

        log.info("load logics queue all over, OK");

        return true;
    }

    private void recover(final boolean lastExitOK) {
        this.recoverConsumeQueue();

        if (lastExitOK) {
            this.commitLog.recoverNormally();
        } else {
            this.commitLog.recoverAbnormally();
        }

        this.recoverTopicQueueTable();
    }

    public MessageStoreConfig getMessageStoreConfig() {
        return messageStoreConfig;
    }

    public TransientStorePool getTransientStorePool() {
        return transientStorePool;
    }

    private void putConsumeQueue(final String topic, final int queueId, final ConsumeQueue consumeQueue) {
        ConcurrentHashMap<Integer/* queueId */, ConsumeQueue> map = this.consumeQueueTable.get(topic);
        if (null == map) {
            map = new ConcurrentHashMap<Integer/* queueId */, ConsumeQueue>();
            map.put(queueId, consumeQueue);
            this.consumeQueueTable.put(topic, map);
        } else {
            map.put(queueId, consumeQueue);
        }
    }

    private void recoverConsumeQueue() {
        for (ConcurrentHashMap<Integer, ConsumeQueue> maps : this.consumeQueueTable.values()) {
            for (ConsumeQueue logic : maps.values()) {
                logic.recover();
            }
        }
    }

    private void recoverTopicQueueTable() {
        HashMap<String/* topic-queueid */, Long/* offset */> table = new HashMap<String, Long>(1024);
        long minPhyOffset = this.commitLog.getMinOffset();
        for (ConcurrentHashMap<Integer, ConsumeQueue> maps : this.consumeQueueTable.values()) {
            for (ConsumeQueue logic : maps.values()) {
                String key = logic.getTopic() + "-" + logic.getQueueId();
                table.put(key, logic.getMaxOffsetInQueue());
                logic.correctMinOffset(minPhyOffset);
            }
        }

        this.commitLog.setTopicQueueTable(table);
    }

    public AllocateMappedFileService getAllocateMappedFileService() {
        return allocateMappedFileService;
    }

    public StoreStatsService getStoreStatsService() {
        return storeStatsService;
    }

    public RunningFlags getAccessRights() {
        return runningFlags;
    }

    public ConcurrentHashMap<String, ConcurrentHashMap<Integer, ConsumeQueue>> getConsumeQueueTable() {
        return consumeQueueTable;
    }

    public StoreCheckpoint getStoreCheckpoint() {
        return storeCheckpoint;
    }

    public HAService getHaService() {
        return haService;
    }

    public ScheduleMessageService getScheduleMessageService() {
        return scheduleMessageService;
    }

    public RunningFlags getRunningFlags() {
        return runningFlags;
    }

    /**
     * 执行调度请求
     * 1. 非事务消息 或 事务提交消息 建立 消息位置信息 到 ConsumeQueue
     * 2. 建立 索引信息 到 IndexFile
     *
     * @param req 调度请求
     */
    public void doDispatch(DispatchRequest req) {
        // 非事务消息 或 事务提交消息 建立 消息位置信息 到 ConsumeQueue
        final int tranType = MessageSysFlag.getTransactionValue(req.getSysFlag());
        switch (tranType) {
            case MessageSysFlag.TRANSACTION_NOT_TYPE: // 非事务消息
            case MessageSysFlag.TRANSACTION_COMMIT_TYPE: // 事务消息COMMIT
                DefaultMessageStore.this.putMessagePositionInfo(req.getTopic(), req.getQueueId(), req.getCommitLogOffset(), req.getMsgSize(),
                    req.getTagsCode(), req.getStoreTimestamp(), req.getConsumeQueueOffset());
                break;
            case MessageSysFlag.TRANSACTION_PREPARED_TYPE: // 事务消息PREPARED
            case MessageSysFlag.TRANSACTION_ROLLBACK_TYPE: // 事务消息ROLLBACK
                break;
        }
        // 建立 索引信息 到 IndexFile
        if (DefaultMessageStore.this.getMessageStoreConfig().isMessageIndexEnable()) {
            DefaultMessageStore.this.indexService.buildIndex(req);
        }
    }

    /**
     * 建立 消息位置信息 到 ConsumeQueue
     *
     * @param topic          主题
     * @param queueId        队列编号
     * @param offset         commitLog存储位置
     * @param size           消息长度
     * @param tagsCode       Message Tag HashCode ,主要用于订阅时消息过滤（订阅时如果指定了Tag，会根据HashCode来快速查找到订阅的消息）
     * @param storeTimestamp 存储时间
     * @param logicOffset    队列位置
     */
    public void putMessagePositionInfo(String topic, int queueId, long offset, int size, long tagsCode, long storeTimestamp,
                                       long logicOffset) {
        ConsumeQueue cq = this.findConsumeQueue(topic, queueId);
        cq.putMessagePositionInfoWrapper(offset, size, tagsCode, storeTimestamp, logicOffset);
    }

    public BrokerStatsManager getBrokerStatsManager() {
        return brokerStatsManager;
    }

    public int remainTransientStoreBufferNumbs() {
        return this.transientStorePool.remainBufferNumbs();
    }

    @Override
    public boolean isTransientStorePoolDeficient() {
        return remainTransientStoreBufferNumbs() == 0;
    }

    public void unlockMappedFile(final MappedFile mappedFile) {
        this.scheduledExecutorService.schedule(new Runnable() {
            @Override
            public void run() {
                mappedFile.munlock();
            }
        }, 6, TimeUnit.SECONDS);
    }

    class CleanCommitLogService {

        private final static int MAX_MANUAL_DELETE_FILE_TIMES = 20;
        private final double diskSpaceWarningLevelRatio =
            Double.parseDouble(System.getProperty("rocketmq.broker.diskSpaceWarningLevelRatio", "0.90"));

        private final double diskSpaceCleanForciblyRatio =
            Double.parseDouble(System.getProperty("rocketmq.broker.diskSpaceCleanForciblyRatio", "0.85"));
        private long lastRedeleteTimestamp = 0;

        private volatile int manualDeleteFileSeveralTimes = 0;

        private volatile boolean cleanImmediately = false;

        public void excuteDeleteFilesManualy() {
            this.manualDeleteFileSeveralTimes = MAX_MANUAL_DELETE_FILE_TIMES;
            DefaultMessageStore.log.info("excuteDeleteFilesManualy was invoked");
        }

        public void run() {
            try {
                this.deleteExpiredFiles();

                this.redeleteHangedFile();
            } catch (Exception e) {
                DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
            }
        }

        private void deleteExpiredFiles() {
            int deleteCount = 0;
            long fileReservedTime = DefaultMessageStore.this.getMessageStoreConfig().getFileReservedTime(); // 文件保留时间,默认72
            int deletePhysicFilesInterval = DefaultMessageStore.this.getMessageStoreConfig().getDeleteCommitLogFilesInterval(); // 删除文件间隔,100
            int destroyMapedFileIntervalForcibly = DefaultMessageStore.this.getMessageStoreConfig().getDestroyMapedFileIntervalForcibly(); // 1000 * 120

            boolean timeup = this.isTimeToDelete();  // 删除时间默认凌晨4点
            boolean spacefull = this.isSpaceToDelete();
            boolean manualDelete = this.manualDeleteFileSeveralTimes > 0;

            if (timeup || spacefull || manualDelete) {

                if (manualDelete) { this.manualDeleteFileSeveralTimes--; }

                boolean cleanAtOnce = DefaultMessageStore.this.getMessageStoreConfig().isCleanFileForciblyEnable() && this.cleanImmediately;

                log.info("begin to delete before {} hours file. timeup: {} spacefull: {} manualDeleteFileSeveralTimes: {} cleanAtOnce: {}",
                    fileReservedTime,
                    timeup,
                    spacefull,
                    manualDeleteFileSeveralTimes,
                    cleanAtOnce);

                fileReservedTime *= 60 * 60 * 1000;  // 72h

                deleteCount = DefaultMessageStore.this.commitLog.deleteExpiredFile(fileReservedTime, deletePhysicFilesInterval,
                    destroyMapedFileIntervalForcibly, cleanAtOnce);
                if (deleteCount > 0) {
                } else if (spacefull) {
                    log.warn("disk space will be full soon, but delete file failed.");
                }
            }
        }

        private void redeleteHangedFile() {
            int interval = DefaultMessageStore.this.getMessageStoreConfig().getRedeleteHangedFileInterval();
            long currentTimestamp = System.currentTimeMillis();
            if ((currentTimestamp - this.lastRedeleteTimestamp) > interval) {
                this.lastRedeleteTimestamp = currentTimestamp;
                int destroyMapedFileIntervalForcibly =
                    DefaultMessageStore.this.getMessageStoreConfig().getDestroyMapedFileIntervalForcibly();
                if (DefaultMessageStore.this.commitLog.retryDeleteFirstFile(destroyMapedFileIntervalForcibly)) {
                }
            }
        }

        public String getServiceName() {
            return CleanCommitLogService.class.getSimpleName();
        }

        private boolean isTimeToDelete() {
            String when = DefaultMessageStore.this.getMessageStoreConfig().getDeleteWhen();
            if (UtilAll.isItTimeToDo(when)) {
                DefaultMessageStore.log.info("it's time to reclaim disk space, " + when);
                return true;
            }

            return false;
        }

        private boolean isSpaceToDelete() {
            double ratio = DefaultMessageStore.this.getMessageStoreConfig().getDiskMaxUsedSpaceRatio() / 100.0;

            cleanImmediately = false;

            {
                String storePathPhysic = DefaultMessageStore.this.getMessageStoreConfig().getStorePathCommitLog();
                double physicRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathPhysic);
                if (physicRatio > diskSpaceWarningLevelRatio) {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskFull();
                    if (diskok) {
                        DefaultMessageStore.log.error("physic disk maybe full soon " + physicRatio + ", so mark disk full");
                    }

                    cleanImmediately = true;
                } else if (physicRatio > diskSpaceCleanForciblyRatio) {
                    cleanImmediately = true;
                } else {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskOK();
                    if (!diskok) {
                        DefaultMessageStore.log.info("physic disk space OK " + physicRatio + ", so mark disk ok");
                    }
                }

                if (physicRatio < 0 || physicRatio > ratio) {
                    DefaultMessageStore.log.info("physic disk maybe full soon, so reclaim space, " + physicRatio);
                    return true;
                }
            }

            {
                String storePathLogics = StorePathConfigHelper
                    .getStorePathConsumeQueue(DefaultMessageStore.this.getMessageStoreConfig().getStorePathRootDir());
                double logicsRatio = UtilAll.getDiskPartitionSpaceUsedPercent(storePathLogics);
                if (logicsRatio > diskSpaceWarningLevelRatio) {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskFull();
                    if (diskok) {
                        DefaultMessageStore.log.error("logics disk maybe full soon " + logicsRatio + ", so mark disk full");
                    }

                    cleanImmediately = true;
                } else if (logicsRatio > diskSpaceCleanForciblyRatio) {
                    cleanImmediately = true;
                } else {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskOK();
                    if (!diskok) {
                        DefaultMessageStore.log.info("logics disk space OK " + logicsRatio + ", so mark disk ok");
                    }
                }

                if (logicsRatio < 0 || logicsRatio > ratio) {
                    DefaultMessageStore.log.info("logics disk maybe full soon, so reclaim space, " + logicsRatio);
                    return true;
                }
            }

            return false;
        }

        public int getManualDeleteFileSeveralTimes() {
            return manualDeleteFileSeveralTimes;
        }

        public void setManualDeleteFileSeveralTimes(int manualDeleteFileSeveralTimes) {
            this.manualDeleteFileSeveralTimes = manualDeleteFileSeveralTimes;
        }
    }

    class CleanConsumeQueueService {

        private long lastPhysicalMinOffset = 0;

        public void run() {
            try {
                this.deleteExpiredFiles();
            } catch (Exception e) {
                DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
            }
        }

        /**
         * 当CommitLog删除了过期的MappedFile,ConsumeQueue也删除对应的消费文件
         * 从CommitLog的minOffset开始
         * 也就是说ConsumeQueue的MappedFile里第一个unit的commitLogOffset < minOffset, 则删除此MappedFile
         */
        private void deleteExpiredFiles() {
            int deleteLogicsFilesInterval = DefaultMessageStore.this.getMessageStoreConfig().getDeleteConsumeQueueFilesInterval(); //100

            long minOffset = DefaultMessageStore.this.commitLog.getMinOffset();
            if (minOffset > this.lastPhysicalMinOffset) {
                this.lastPhysicalMinOffset = minOffset;

                ConcurrentHashMap<String, ConcurrentHashMap<Integer, ConsumeQueue>> tables = DefaultMessageStore.this.consumeQueueTable;

                for (ConcurrentHashMap<Integer, ConsumeQueue> maps : tables.values()) {
                    for (ConsumeQueue logic : maps.values()) {
                        int deleteCount = logic.deleteExpiredFile(minOffset);
                        // 每删除成功一个休眠100ms
                        if (deleteCount > 0 && deleteLogicsFilesInterval > 0) {
                            try {
                                Thread.sleep(deleteLogicsFilesInterval);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                }

                DefaultMessageStore.this.indexService.deleteExpiredFile(minOffset);
            }
        }

        public String getServiceName() {
            return CleanConsumeQueueService.class.getSimpleName();
        }
    }

    /**
     * flush ConsumeQueue 线程服务
     */
    class FlushConsumeQueueService extends ServiceThread {

        private static final int RETRY_TIMES_OVER = 3;
        /**
         * 最后flush时间戳
         */
        private long lastFlushTimestamp = 0;

        /**
         * 每隔1S执行一次
         *
         * @param retryTimes 默认1次, shutdown时3次
         */
        private void doFlush(int retryTimes) {
            int flushConsumeQueueLeastPages = DefaultMessageStore.this.getMessageStoreConfig().getFlushConsumeQueueLeastPages(); //最少刷新2页

            // retryTimes == RETRY_TIMES_OVER时，进行强制flush。主要用于shutdown时。
            if (retryTimes == RETRY_TIMES_OVER) {
                flushConsumeQueueLeastPages = 0;
            }
            long logicsMsgTimestamp = 0;
            int flushConsumeQueueThoroughInterval = DefaultMessageStore.this.getMessageStoreConfig().getFlushConsumeQueueThoroughInterval();  //60
            long currentTimeMillis = System.currentTimeMillis();
            // 当距离上次flush的间隔 >= 60S时，即使写入的数量不足flushConsumeQueueLeastPages，也进行flush
            if (currentTimeMillis >= (this.lastFlushTimestamp + flushConsumeQueueThoroughInterval)) {
                this.lastFlushTimestamp = currentTimeMillis;
                flushConsumeQueueLeastPages = 0;
                logicsMsgTimestamp = DefaultMessageStore.this.getStoreCheckpoint().getLogicsMsgTimestamp();
            }
            // flush消费队列,默认情况下仅进行一次flush
            ConcurrentHashMap<String, ConcurrentHashMap<Integer, ConsumeQueue>> tables = DefaultMessageStore.this.consumeQueueTable;
            for (ConcurrentHashMap<Integer, ConsumeQueue> maps : tables.values()) {
                for (ConsumeQueue cq : maps.values()) {
                    boolean result = false;
                    for (int i = 0; i < retryTimes && !result; i++) {
                        result = cq.flush(flushConsumeQueueLeastPages);
                    }
                }
            }
            // flush 存储 check point
            if (0 == flushConsumeQueueLeastPages) {
                if (logicsMsgTimestamp > 0) {
                    DefaultMessageStore.this.getStoreCheckpoint().setLogicsMsgTimestamp(logicsMsgTimestamp);
                }
                DefaultMessageStore.this.getStoreCheckpoint().flush();
            }
        }

        public void run() {
            DefaultMessageStore.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    //1S,每隔1S执行一次Flush
                    int interval = DefaultMessageStore.this.getMessageStoreConfig().getFlushIntervalConsumeQueue();
                    this.waitForRunning(interval);
                    this.doFlush(1);
                } catch (Exception e) {
                    DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            this.doFlush(RETRY_TIMES_OVER);

            DefaultMessageStore.log.info(this.getServiceName() + " service end");
        }

        @Override
        public String getServiceName() {
            return FlushConsumeQueueService.class.getSimpleName();
        }

        @Override
        public long getJointime() {
            return 1000 * 60;
        }
    }

    /**
     * 重放消息线程服务
     * 该服务不断生成 消息位置信息 到 消费队列(ConsumeQueue)
     * 该服务不断生成 消息索引 到 索引文件(IndexFile)
     */
    class ReputMessageService extends ServiceThread {

        /**
         * 开始重放消息的CommitLog物理位置
         */
        private volatile long reputFromOffset = 0;

        public long getReputFromOffset() {
            return reputFromOffset;
        }

        public void setReputFromOffset(long reputFromOffset) {
            this.reputFromOffset = reputFromOffset;
        }

        @Override
        public void shutdown() {
            for (int i = 0; i < 50 && this.isCommitLogAvailable(); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }

            if (this.isCommitLogAvailable()) {
                log.warn("shutdown ReputMessageService, but commitlog have not finish to be dispatched, CL: {} reputFromOffset: {}",
                    DefaultMessageStore.this.commitLog.getMaxOffset(), this.reputFromOffset);
            }

            super.shutdown();
        }

        /**
         * 剩余需要重放消息字节数
         *
         * @return 字节数
         */
        public long behind() {
            return DefaultMessageStore.this.commitLog.getMaxOffset() - this.reputFromOffset;
        }

        /**
         * 是否commitLog需要重放消息
         * reputFromOffset < 最后一个MappedFile的文件名称+此文件的wrotePotision
         *
         * @return 是否
         */
        private boolean isCommitLogAvailable() {
            return this.reputFromOffset < DefaultMessageStore.this.commitLog.getMaxOffset();
        }

        private void doReput() {
            for (boolean doNext = true; this.isCommitLogAvailable() && doNext; ) {

                if (DefaultMessageStore.this.getMessageStoreConfig().isDuplicationEnable()
                    && this.reputFromOffset >= DefaultMessageStore.this.getConfirmOffset()) {
                    break;
                }

                // 获取从reputFromOffset开始到最后一个MappedFile的wrotePotision的数据的引用
                SelectMappedBufferResult result = DefaultMessageStore.this.commitLog.getData(reputFromOffset);
                if (result != null) {
                    try {
                        this.reputFromOffset = result.getStartOffset();

                        //每读一轮,消息前4字节表示消息总长度,按消息存储结构读取,如果还有剩余的就继续读
                        for (int readSize = 0; readSize < result.getSize() && doNext; ) {
                            // 生成重放消息重放调度请求,从mappedByteBuffer中读取字节，解析成消息
                            DispatchRequest dispatchRequest = DefaultMessageStore.this.commitLog.checkMessageAndReturnSize(result.getByteBuffer(), false,
                                false);
                            int size = dispatchRequest.getMsgSize(); // 消息总长度
                            // 根据请求的结果处理
                            if (dispatchRequest.isSuccess()) {
                                if (size > 0) {
                                    // 成功读取Message,更新ConsumeQueue里的位置信息,更新IndexFile
                                    DefaultMessageStore.this.doDispatch(dispatchRequest);
                                    // 通知有新消息
                                    if (BrokerRole.SLAVE != DefaultMessageStore.this.getMessageStoreConfig().getBrokerRole()
                                        && DefaultMessageStore.this.brokerConfig.isLongPollingEnable()) {
                                        DefaultMessageStore.this.messageArrivingListener.arriving(dispatchRequest.getTopic(),
                                            dispatchRequest.getQueueId(), dispatchRequest.getConsumeQueueOffset() + 1,
                                            dispatchRequest.getTagsCode());
                                    }
                                    // FIXED BUG By shijia
                                    this.reputFromOffset += size;
                                    readSize += size;
                                    // 统计
                                    if (DefaultMessageStore.this.getMessageStoreConfig().getBrokerRole() == BrokerRole.SLAVE) {
                                        DefaultMessageStore.this.storeStatsService
                                            .getSinglePutMessageTopicTimesTotal(dispatchRequest.getTopic()).incrementAndGet();
                                        DefaultMessageStore.this.storeStatsService
                                            .getSinglePutMessageTopicSizeTotal(dispatchRequest.getTopic())
                                            .addAndGet(dispatchRequest.getMsgSize());
                                    }
                                } else if (size == 0) { // 读取到MappedFile文件尾
                                    this.reputFromOffset = DefaultMessageStore.this.commitLog.rollNextFile(this.reputFromOffset);
                                    readSize = result.getSize();
                                }
                            } else if (!dispatchRequest.isSuccess()) { // 读取失败
                                if (size > 0) { // 读取到Message却不是Message
                                    log.error("[BUG]read total count not equals msg total size. reputFromOffset={}", reputFromOffset);
                                    this.reputFromOffset += size;
                                } else { // 读取到Blank却不是Blank
                                    doNext = false;
                                    if (DefaultMessageStore.this.brokerConfig.getBrokerId() == MixAll.MASTER_ID) {
                                        log.error("[BUG]the master dispatch message to consume queue error, COMMITLOG OFFSET: {}",
                                            this.reputFromOffset);

                                        this.reputFromOffset += result.getSize() - readSize;
                                    }
                                }
                            }
                        }
                    } finally {
                        result.release();
                    }
                } else {
                    doNext = false;
                }
            }
        }

        @Override
        public void run() {
            DefaultMessageStore.log.info(this.getServiceName() + " service started");

            while (!this.isStopped()) {
                try {
                    Thread.sleep(1);  //每一毫秒执行一次Reput
                    this.doReput();
                } catch (Exception e) {
                    DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            DefaultMessageStore.log.info(this.getServiceName() + " service end");
        }

        @Override
        public String getServiceName() {
            return ReputMessageService.class.getSimpleName();
        }

    }
}
