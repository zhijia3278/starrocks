// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.rpc;

import com.starrocks.common.ClientPool;
import com.starrocks.thrift.FrontendService;
import com.starrocks.thrift.TNetworkAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TTransportException;

import java.net.SocketTimeoutException;

public class FrontendServiceProxy {
    private static final Logger LOG = LogManager.getLogger(FrontendServiceProxy.class);

    public static <T> T call(TNetworkAddress address, int timeoutMs, int retryTimes, MethodCallable<T> callable)
            throws Exception {
        FrontendService.Client client = ClientPool.frontendPool.borrowObject(address, timeoutMs);
        boolean isConnValid = false;
        try {
            for (int i = 0; i < retryTimes; i++) {
                try {
                    T t = callable.invoke(client);
                    isConnValid = true;
                    return t;
                } catch (TTransportException te) {
                    LOG.warn("call frontend thrift rpc failed, addr: {}, retried: {}", address, i, te);
                    // The frontendPool may return a broken conn,
                    // because there is no validation of the conn in the frontendPool.
                    // In this case we should reopen the conn and retry the rpc call,
                    // but we do not retry for the timeout exception, because it may be a network timeout
                    // or the target server may be running slow.
                    isConnValid = ClientPool.frontendPool.reopen(client, timeoutMs);
                    if (i == retryTimes - 1 ||
                            !isConnValid ||
                            (te.getCause() instanceof SocketTimeoutException)) {
                        throw te;
                    }
                }
            }
        } finally {
            if (isConnValid) {
                ClientPool.frontendPool.returnObject(address, client);
            } else {
                ClientPool.frontendPool.invalidateObject(address, client);
            }
        }

        throw new Exception("unexpected");
    }

    public interface MethodCallable<T> {
        T invoke(FrontendService.Client client) throws TException;
    }
}
