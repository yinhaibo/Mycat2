package io.mycat.vertx;

import io.mycat.MySQLPacketUtil;
import io.mycat.MycatDataContext;
import io.mycat.TransactionSession;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.proxy.session.ProcessState;
import io.mycat.runtime.MycatDataContextImpl;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.nio.charset.Charset;

public class VertxSessionImpl implements VertxSession {
    private MycatDataContextImpl mycatDataContext;
    private NetSocket socket;
    int packetId = 0;
    private ProcessState processState;

    public VertxSessionImpl(MycatDataContextImpl mycatDataContext, NetSocket socket) {
        this.mycatDataContext = mycatDataContext;

        this.socket = socket;
    }

    @Override
    public void setPacketId(int packet) {
        this.packetId = packet;
    }

    @Override
    public byte getNextPacketId() {
        return (byte) (++packetId);
    }

    @Override
    public String getLastMessage() {
        return mycatDataContext.getLastMessage();
    }

    @Override
    public long affectedRows() {
        return mycatDataContext.getAffectedRows();
    }

    @Override
    public int getServerStatusValue() {
        return mycatDataContext.getServerStatus();
    }

    @Override
    public int getWarningCount() {
        return mycatDataContext.getWarningCount();
    }

    @Override
    public boolean isDeprecateEOF() {
        return MySQLServerCapabilityFlags.isDeprecateEOF(this.mycatDataContext.getServerCapabilities());
    }

    @Override
    public long getLastInsertId() {
        return mycatDataContext.getLastInsertId();
    }

    @Override
    public int getLastErrorCode() {
        return mycatDataContext.getLastErrorCode();
    }

    @Override
    public void setLastErrorCode(int errorCode) {
        mycatDataContext.setLastErrorCode(errorCode);
    }

    @Override
    public int getCapabilities() {
        return this.mycatDataContext.getServerCapabilities();
    }

    @Override
    public boolean isResponseFinished() {
        return processState == ProcessState.DONE;
    }

    @Override
    public int charsetIndex() {
        return this.mycatDataContext.getCharsetIndex();
    }

    @Override
    public void setResponseFinished(ProcessState b) {
        this.processState = b;
    }

    @Override
    public void resetSession() {

    }

    @Override
    public Charset charset() {
        return this.mycatDataContext.getCharset();
    }

    @Override
    public void writeBytes(byte[] payload, boolean end) {
        if (end) {
            if (mycatDataContext!=null){
                TransactionSession transactionSession = mycatDataContext.getTransactionSession();
                if (transactionSession!=null){
                    transactionSession.closeStatenmentState();
                }
            }
        }
        socket.write(Buffer.buffer(MySQLPacketUtil.generateMySQLPacket(getNextPacketId(), payload)));
        if (end) {
            this.socket.resume();
        }
    }

    @Override
    public void writeErrorEndPacketBySyncInProcessError() {
        writeBytes(MySQLPacketUtil.generateError(
                mycatDataContext.getLastErrorCode(),
                mycatDataContext.getLastMessage(),
                getCapabilities()
        ), true);
    }

    @Override
    public void writeErrorEndPacketBySyncInProcessError(int errorCode) {
        writeBytes(MySQLPacketUtil.generateError(
                errorCode,
                mycatDataContext.getLastMessage(),
                getCapabilities()
        ), true);
    }

    @Override
    public MycatDataContext getDataContext() {
        return mycatDataContext;
    }

    @Override
    public void close() {
        mycatDataContext.close();
        socket.close();
    }

    @Override
    public NetSocket getSocket() {
        return socket;
    }
}
