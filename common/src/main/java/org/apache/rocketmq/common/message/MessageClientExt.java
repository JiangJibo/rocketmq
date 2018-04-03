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
package org.apache.rocketmq.common.message;

import java.nio.ByteBuffer;

/**
 * 在客户端的MessageExt
 * 使用Client为消息生成的uniqID来替代msgId
 */
public class MessageClientExt extends MessageExt {

    public String getOffsetMsgId() {
        return super.getMsgId();
    }

    public void setOffsetMsgId(String offsetMsgId) {
        super.setMsgId(offsetMsgId);
    }

    /**
     * 在客户端,使用uniqID来替代msgId
     * 在服务端,使用ip:port+commitLog来替代msgId
     *
     * @return
     * @see MessageDecoder#decode(ByteBuffer, boolean, boolean, boolean)
     */
    @Override
    public String getMsgId() {
        String uniqID = MessageClientIDSetter.getUniqID(this);
        if (uniqID != null) {
            return uniqID;
        } else {
            return this.getOffsetMsgId();
        }
    }

    public void setMsgId(String msgId) {
        //DO NOTHING
        //MessageClientIDSetter.setUniqID(this);
    }
}
