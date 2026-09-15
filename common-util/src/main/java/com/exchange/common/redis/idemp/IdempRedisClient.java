package com.exchange.common.redis.idemp;

import com.exchange.common.redis.RedisValueSupport;
import com.exchange.common.redis.component.RedisScriptExecutor;
import com.exchange.common.result.Result;
import com.exchange.common.redis.idemp.constant.CommonIdempStatus;
import com.exchange.common.redis.idemp.utils.CommonIdempHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

import java.time.Duration;

@Slf4j
@RequiredArgsConstructor
public class IdempRedisClient {
    private final RedisValueSupport<String> baseRedisSupport;
    private final RedissonClient redissonClient;
    private final RedisScriptExecutor scriptExecutor;

    public Boolean claimIdempIfAbsent(String service, String scope, String idempId, String hash, String token, Duration ttl) {
        String k = CommonIdempHelper.idempKey(service, scope, idempId);
        String v = CommonIdempHelper.idempPendingValue(hash, token);
        return baseRedisSupport.setIfAbsent(k, v, ttl);
    }

    public void markIdempDone(String service, String scope, String idempId, String hash, String token, String content, Duration ttl) {
        // TODO(idempotency-owned-completion): Replace this unconditional SET with one atomic Lua compare-and-set.
        //  Transition to ACCEPTED only when the current value is PENDING and is still owned by `token`;
        //  an expired, missing, or moved claim must not be recreated or overwrite another owner. Return a
        //  structured outcome so callers can keep an already committed DB result successful when ownership is lost.
        //  Target protocol: design/proposals/redis-idempotency-db-authoritative.md.
        String k = CommonIdempHelper.idempKey(service, scope, idempId);
        String v = CommonIdempHelper.idempValue(CommonIdempStatus.ACCEPTED, hash, token, content);
        baseRedisSupport.set(k, v, ttl);
    }

    public String getIdemp(String service, String scope, String idempId) {
        return baseRedisSupport.get(CommonIdempHelper.idempKey(service, scope, idempId));
    }

    // TODO return String instead
    public Result<String> releaseIdempIfOwned(String service, String scope, String idempId, String value) {
        String key = CommonIdempHelper.idempKey(service, scope, idempId);
        return Result.success(scriptExecutor.releaseIdempIfOwned(redissonClient, key, value));
    }

    public Boolean forceDeleteIdemp(String service, String scope, String idempId) {
        String k = CommonIdempHelper.idempKey(service, scope, idempId);
        return baseRedisSupport.delete(k);
    }
}
