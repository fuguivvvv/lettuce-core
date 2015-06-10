// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.lambdaworks.redis.LettuceStrings.isEmpty;
import static com.lambdaworks.redis.LettuceStrings.isNotEmpty;

import java.net.ConnectException;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.base.Supplier;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import com.lambdaworks.redis.api.async.RedisSentinelAsyncConnection;
import com.lambdaworks.redis.codec.RedisCodec;
import com.lambdaworks.redis.codec.Utf8StringCodec;
import com.lambdaworks.redis.protocol.CommandHandler;
import com.lambdaworks.redis.protocol.RedisCommand;
import com.lambdaworks.redis.pubsub.PubSubCommandHandler;
import com.lambdaworks.redis.pubsub.RedisPubSubAsyncConnection;
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnectionImpl;
import com.lambdaworks.redis.sentinel.StatefulRedisSentinelConnectionImpl;
import com.lambdaworks.redis.sentinel.api.StatefulRedisSentinelConnection;

/**
 * A scalable thread-safe <a href="http://redis.io/">Redis</a> client. Multiple threads may share one connection provided they
 * avoid blocking and transactional operations such as BLPOP and MULTI/EXEC.
 * 
 * @author Will Glozer
 * @author <a href="mailto:mpaluch@paluch.biz">Mark Paluch</a>
 */
public class RedisClient extends AbstractRedisClient {

    private final RedisURI redisURI;

    /**
     * Creates a uri-less RedisClient. You can connect to different redis servers but you must supply a {@link RedisURI} on
     * connecting. Methods without having a {@link RedisURI} will fail with a {@link java.lang.IllegalStateException}.
     */
    public RedisClient() {
        redisURI = null;
        setDefaultTimeout(60, TimeUnit.MINUTES);
    }

    /**
     * Create a new client that connects to the supplied host on the default port.
     * 
     * @param host Server hostname.
     */
    public RedisClient(String host) {
        this(host, RedisURI.DEFAULT_REDIS_PORT);
    }

    /**
     * Create a new client that connects to the supplied host and port. Connection attempts and non-blocking commands will
     * {@link #setDefaultTimeout timeout} after 60 seconds.
     * 
     * @param host Server hostname.
     * @param port Server port.
     */
    public RedisClient(String host, int port) {
        this(RedisURI.Builder.redis(host, port).build());
    }

    /**
     * Create a new client that connects to the supplied host and port. Connection attempts and non-blocking commands will
     * {@link #setDefaultTimeout timeout} after 60 seconds.
     * 
     * @param redisURI Redis URI.
     */
    public RedisClient(RedisURI redisURI) {
        super();
        this.redisURI = redisURI;
        setDefaultTimeout(redisURI.getTimeout(), redisURI.getUnit());
    }

    /**
     * Creates a connection pool for synchronous connections. 5 max idle connections and 20 max active connections. Please keep
     * in mind to free all collections and close the pool once you do not need it anymore.
     * 
     * @return a new {@link RedisConnectionPool} instance
     */
    public RedisConnectionPool<RedisConnection<String, String>> pool() {
        return pool(5, 20);
    }

    /**
     * Creates a connection pool for synchronous connections. Please keep in mind to free all collections and close the pool
     * once you do not need it anymore.
     * 
     * @param maxIdle max idle connections in pool
     * @param maxActive max active connections in pool
     * @return a new {@link RedisConnectionPool} instance
     */
    public RedisConnectionPool<RedisConnection<String, String>> pool(int maxIdle, int maxActive) {

        return pool(newStringStringCodec(), maxIdle, maxActive);
    }

    /**
     * Creates a connection pool for synchronous connections. Please keep in mind to free all collections and close the pool
     * once you do not need it anymore.
     * 
     * @param codec the codec to use
     * @param maxIdle max idle connections in pool
     * @param maxActive max active connections in pool
     * @param <K> Key type.
     * @param <V> Value type.
     * @return a new {@link RedisConnectionPool} instance
     */
    @SuppressWarnings("unchecked")
    public <K, V> RedisConnectionPool<RedisConnection<K, V>> pool(final RedisCodec<K, V> codec, int maxIdle, int maxActive) {

        checkForRedisURI();

        long maxWait = makeTimeout();
        RedisConnectionPool<RedisConnection<K, V>> pool = new RedisConnectionPool<RedisConnection<K, V>>(
                new RedisConnectionPool.RedisConnectionProvider<RedisConnection<K, V>>() {
                    @Override
                    public RedisConnection<K, V> createConnection() {
                        return connect(codec, redisURI);
                    }

                    @Override
                    @SuppressWarnings("rawtypes")
                    public Class<? extends RedisConnection<K, V>> getComponentType() {
                        return (Class) RedisConnection.class;
                    }
                }, maxActive, maxIdle, maxWait);

        pool.addListener(new CloseEvents.CloseListener() {
            @Override
            public void resourceClosed(Object resource) {
                closeableResources.remove(resource);
            }
        });

        closeableResources.add(pool);

        return pool;
    }

    protected long makeTimeout() {
        return TimeUnit.MILLISECONDS.convert(timeout, unit);
    }

    private void checkForRedisURI() {
        checkState(this.redisURI != null,
                "RedisURI is not available. Use RedisClient(Host), RedisClient(Host, Port) or RedisClient(RedisURI) to construct your client.");
    }

    /**
     * Creates a connection pool for asynchronous connections. 5 max idle connections and 20 max active connections. Please keep
     * in mind to free all collections and close the pool once you do not need it anymore.
     * 
     * @return a new {@link RedisConnectionPool} instance
     */
    public RedisConnectionPool<RedisAsyncConnection<String, String>> asyncPool() {
        return asyncPool(5, 20);
    }

    /**
     * Creates a connection pool for asynchronous connections. Please keep in mind to free all collections and close the pool
     * once you do not need it anymore.
     * 
     * @param maxIdle max idle connections in pool
     * @param maxActive max active connections in pool
     * @return a new {@link RedisConnectionPool} instance
     */
    public RedisConnectionPool<RedisAsyncConnection<String, String>> asyncPool(int maxIdle, int maxActive) {

        return asyncPool(newStringStringCodec(), maxIdle, maxActive);
    }

    /**
     * Creates a connection pool for asynchronous connections. Please keep in mind to free all collections and close the pool
     * once you do not need it anymore.
     * 
     * @param codec the codec to use
     * @param maxIdle max idle connections in pool
     * @param maxActive max active connections in pool
     * @param <K> Key type.
     * @param <V> Value type.
     * @return a new {@link RedisConnectionPool} instance
     */
    public <K, V> RedisConnectionPool<RedisAsyncConnection<K, V>> asyncPool(final RedisCodec<K, V> codec, int maxIdle,
            int maxActive) {

        checkForRedisURI();
        long maxWait = makeTimeout();
        RedisConnectionPool<RedisAsyncConnection<K, V>> pool = new RedisConnectionPool<RedisAsyncConnection<K, V>>(
                new RedisConnectionPool.RedisConnectionProvider<RedisAsyncConnection<K, V>>() {
                    @Override
                    public RedisAsyncConnection<K, V> createConnection() {
                        return connectStateful(codec, redisURI).async();
                    }

                    @Override
                    @SuppressWarnings({ "rawtypes", "unchecked" })
                    public Class<? extends RedisAsyncConnection<K, V>> getComponentType() {
                        return (Class) RedisAsyncConnection.class;
                    }
                }, maxActive, maxIdle, maxWait);

        pool.addListener(new CloseEvents.CloseListener() {
            @Override
            public void resourceClosed(Object resource) {
                closeableResources.remove(resource);
            }
        });

        closeableResources.add(pool);

        return pool;
    }

    /**
     * Open a new synchronous connection to the redis server that treats keys and values as UTF-8 strings.
     * 
     * @return A new connection.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public RedisConnection<String, String> connect() {
        return connect(newStringStringCodec());
    }

    /**
     * Open a new synchronous connection to the redis server. Use the supplied {@link RedisCodec codec} to encode/decode keys
     * and values.
     * 
     * @param codec Use this codec to encode/decode keys and values, must note be {@literal null}
     * @param <K> Key type.
     * @param <V> Value type.
     * @return A new connection.
     */
    @SuppressWarnings("unchecked")
    public <K, V> RedisConnection<K, V> connect(RedisCodec<K, V> codec) {
        checkForRedisURI();
        checkArgument(codec != null, "RedisCodec must not be null");
        return connect(codec, this.redisURI);
    }

    /**
     * Open a new synchronous connection to the supplied {@link RedisURI} that treats keys and values as UTF-8 strings.
     *
     * @param redisURI the redis server to connect to, must not be {@literal null}
     * @return A new connection.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public RedisConnection<String, String> connect(RedisURI redisURI) {
        checkValidRedisURI(redisURI);
        return (RedisConnection<String, String>) connect(newStringStringCodec(), redisURI);
    }

    private void checkValidRedisURI(RedisURI redisURI) {
        checkArgument(redisURI != null && isNotEmpty(redisURI.getHost()), "A valid RedisURI with a host is needed");
    }

    @SuppressWarnings({ "rawtypes" })
    private <K, V> RedisConnection connect(RedisCodec<K, V> codec, RedisURI redisURI) {
        return connectStateful(codec, redisURI).sync();
    }

    /**
     * Open a new asynchronous connection to the redis server that treats keys and values as UTF-8 strings.
     * 
     * @return A new connection.
     */
    public RedisAsyncConnection<String, String> connectAsync() {
        return connectAsync(newStringStringCodec());
    }

    /**
     * Open a new asynchronous connection to the redis server. Use the supplied {@link RedisCodec codec} to encode/decode keys
     * and values.
     * 
     * @param codec Use this codec to encode/decode keys and values, must not be {@literal null}
     * @param <K> Key type.
     * @param <V> Value type.
     * @return A new connection.
     */
    public <K, V> RedisAsyncConnection<K, V> connectAsync(RedisCodec<K, V> codec) {
        checkForRedisURI();
        checkArgument(codec != null, "RedisCodec must not be null");
        return connectStateful(codec, redisURI).async();
    }

    /**
     * Open a new asynchronous connection to the supplied {@link RedisURI} that treats keys and values as UTF-8 strings.
     *
     * @param redisURI the redis server to connect to, must not be {@literal null}
     * @return A new connection.
     */
    public RedisAsyncConnection<String, String> connectAsync(RedisURI redisURI) {
        checkValidRedisURI(redisURI);
        return connectStateful(newStringStringCodec(), redisURI).async();
    }

    private <K, V> StatefulRedisConnection<K, V> connectStateful(RedisCodec<K, V> codec, RedisURI redisURI) {
        BlockingQueue<RedisCommand<K, V, ?>> queue = new LinkedBlockingQueue<RedisCommand<K, V, ?>>();

        CommandHandler<K, V> handler = new CommandHandler<K, V>(clientOptions, queue);

        StatefulRedisConnectionImpl connection = newStatefulRedisConnection(handler, codec);
        connectStateful(handler, connection, redisURI);
        return connection;
    }

    private <K, V> void connectStateful(CommandHandler<K, V> handler, StatefulRedisConnectionImpl<K, V> connection,
            RedisURI redisURI) {

        connectImpl(handler, connection, redisURI);

        if (redisURI.getPassword() != null && redisURI.getPassword().length != 0) {
            connection.async().auth(new String(redisURI.getPassword()));
        }

        if (redisURI.getDatabase() != 0) {
            connection.async().select(redisURI.getDatabase());
        }

    }

    private <K, V> void connectImpl(CommandHandler<K, V> handler, RedisChannelHandler<K, V> connection, RedisURI redisURI) {

        ConnectionBuilder connectionBuilder;
        if (redisURI.isSsl()) {
            SslConnectionBuilder sslConnectionBuilder = SslConnectionBuilder.sslConnectionBuilder();
            sslConnectionBuilder.ssl(redisURI);
            connectionBuilder = sslConnectionBuilder;
        } else {
            connectionBuilder = ConnectionBuilder.connectionBuilder();
        }

        connectionBuilder.clientOptions(clientOptions);
        connectionBuilder(handler, connection, getSocketAddressSupplier(redisURI), connectionBuilder, redisURI);
        channelType(connectionBuilder, redisURI);
        initializeChannel(connectionBuilder);

    }

    /**
     * Open a new pub/sub connection to the redis server that treats keys and values as UTF-8 strings.
     *
     * @return A new connection.
     */
    public RedisPubSubAsyncConnection<String, String> connectPubSub() {
        return connectPubSub(newStringStringCodec());
    }

    /**
     * Open a new pub/sub connection to the supplied {@link RedisURI} that treats keys and values as UTF-8 strings.
     *
     * @param redisURI the redis server to connect to, must not be {@literal null}
     * @return A new connection.
     */
    public RedisPubSubAsyncConnection<String, String> connectPubSub(RedisURI redisURI) {
        checkValidRedisURI(redisURI);
        return connectPubSub(newStringStringCodec(), redisURI);
    }

    /**
     * Open a new pub/sub connection to the redis server. Use the supplied {@link RedisCodec codec} to encode/decode keys and
     * values.
     *
     * @param codec Use this codec to encode/decode keys and values, must not be {@literal null}
     * @param <K> Key type.
     * @param <V> Value type.
     * @return A new pub/sub connection.
     */
    public <K, V> RedisPubSubAsyncConnection<K, V> connectPubSub(RedisCodec<K, V> codec) {
        checkForRedisURI();
        return connectPubSub(codec, redisURI);
    }

    protected <K, V> RedisPubSubAsyncConnection<K, V> connectPubSub(RedisCodec<K, V> codec, RedisURI redisURI) {

        checkArgument(codec != null, "RedisCodec must not be null");
        BlockingQueue<RedisCommand<K, V, ?>> queue = new LinkedBlockingQueue<>();

        PubSubCommandHandler<K, V> handler = new PubSubCommandHandler<K, V>(clientOptions, queue, codec);
        StatefulRedisPubSubConnectionImpl<K, V> connection = newStatefulRedisPubSubConnection(handler, codec);

        connectStateful(handler, connection, redisURI);

        return connection.async();
    }

    /**
     * Creates an asynchronous connection to Sentinel. You must supply a valid RedisURI containing one or more sentinels.
     * 
     * @return a new connection.
     */
    public RedisSentinelAsyncConnection<String, String> connectSentinelAsync() {
        return connectSentinelAsync(newStringStringCodec());
    }

    /**
     * Creates an asynchronous connection to Sentinel. You must supply a valid RedisURI containing one or more sentinels.
     * 
     * @param codec Use this codec to encode/decode keys and values, must not be {@literal null}
     * @param <K> Key type.
     * @param <V> Value type.
     * @return a new connection.
     */
    public <K, V> RedisSentinelAsyncConnection<K, V> connectSentinelAsync(RedisCodec<K, V> codec) {
        checkForRedisURI();
        checkArgument(codec != null, "RedisCodec must not be null");
        return connectSentinelImpl(codec, redisURI).async();
    }

    /**
     * Creates an asynchronous connection to Sentinel. You must supply a valid RedisURI containing a redis host or one or more
     * sentinels.
     *
     * @param redisURI the redis server to connect to, must not be {@literal null}
     * @return A new connection.
     */
    public RedisSentinelAsyncConnection<String, String> connectSentinelAsync(RedisURI redisURI) {
        return connectSentinelImpl(newStringStringCodec(), redisURI).async();
    }

    private <K, V> StatefulRedisSentinelConnection<K, V> connectSentinelImpl(RedisCodec<K, V> codec, RedisURI redisURI) {
        BlockingQueue<RedisCommand<K, V, ?>> queue = new LinkedBlockingQueue<RedisCommand<K, V, ?>>();

        ConnectionBuilder connectionBuilder = ConnectionBuilder.connectionBuilder();
        connectionBuilder.clientOptions(ClientOptions.copyOf(getOptions()));

        final CommandHandler<K, V> commandHandler = new CommandHandler<K, V>(clientOptions, queue);

        StatefulRedisSentinelConnectionImpl<K, V> connection = newStatefulRedisSentinelConnection(commandHandler, codec);

        logger.debug("Trying to get a Sentinel connection for one of: " + redisURI.getSentinels());

        connectionBuilder(commandHandler, connection, getSocketAddressSupplier(redisURI), connectionBuilder, redisURI);

        if (redisURI.getSentinels().isEmpty() && (isNotEmpty(redisURI.getHost()) || !isEmpty(redisURI.getSocket()))) {
            channelType(connectionBuilder, redisURI);
            initializeChannel(connectionBuilder);
        } else {
            boolean connected = false;
            boolean first = true;
            Exception causingException = null;
            validateUrisAreOfSameConnectionType(redisURI.getSentinels());
            for (RedisURI uri : redisURI.getSentinels()) {
                if (first) {
                    channelType(connectionBuilder, uri);
                    first = false;
                }
                connectionBuilder.socketAddressSupplier(getSocketAddressSupplier(uri));
                logger.debug("Connecting to Sentinel, address: " + uri.getResolvedAddress());
                try {
                    initializeChannel(connectionBuilder);
                    connected = true;
                    break;
                } catch (Exception e) {
                    logger.warn("Cannot connect sentinel at " + uri + ": " + e.toString());
                    causingException = e;
                    if (e instanceof ConnectException) {
                        continue;
                    }
                }
            }
            if (!connected) {
                throw new RedisConnectionException("Cannot connect to a sentinel: " + redisURI.getSentinels(), causingException);
            }
        }

        return connection;
    }

    protected <K, V> StatefulRedisPubSubConnectionImpl<K, V> newStatefulRedisPubSubConnection(
            PubSubCommandHandler<K, V> handler, RedisCodec<K, V> codec) {
        return new StatefulRedisPubSubConnectionImpl<>(handler, codec, timeout, unit);
    }

    protected <K, V> StatefulRedisSentinelConnectionImpl<K, V> newStatefulRedisSentinelConnection(
            CommandHandler<K, V> commandHandler, RedisCodec<K, V> codec) {
        return new StatefulRedisSentinelConnectionImpl<>(commandHandler, codec, timeout, unit);
    }

    protected <K, V> StatefulRedisConnectionImpl<K, V> newStatefulRedisConnection(CommandHandler<K, V> handler,
            RedisCodec<K, V> codec) {
        return new StatefulRedisConnectionImpl<>(handler, codec, timeout, unit);
    }

    private void validateUrisAreOfSameConnectionType(List<RedisURI> redisUris) {
        boolean unixDomainSocket = false;
        boolean inetSocket = false;
        for (RedisURI sentinel : redisUris) {
            if (sentinel.getSocket() != null) {
                unixDomainSocket = true;
            }
            if (sentinel.getHost() != null) {
                inetSocket = true;
            }
        }

        if (unixDomainSocket && inetSocket) {
            throw new RedisConnectionException("You cannot mix unix domain socket and IP socket URI's");
        }

    }

    private Supplier<SocketAddress> getSocketAddressSupplier(final RedisURI redisURI) {
        return new Supplier<SocketAddress>() {
            @Override
            public SocketAddress get() {
                try {
                    return getSocketAddress(redisURI);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RedisCommandInterruptedException(e);
                } catch (TimeoutException | ExecutionException e) {
                    throw new RedisException(e);
                }
            }
        };
    }

    protected SocketAddress getSocketAddress(RedisURI redisURI) throws InterruptedException, TimeoutException,
            ExecutionException {
        SocketAddress redisAddress;

        if (redisURI.getSentinelMasterId() != null && !redisURI.getSentinels().isEmpty()) {
            logger.debug("Connecting to Redis using Sentinels " + redisURI.getSentinels() + ", MasterId "
                    + redisURI.getSentinelMasterId());
            redisAddress = lookupRedis(redisURI.getSentinelMasterId());

            if (redisAddress == null) {
                throw new RedisConnectionException("Cannot provide redisAddress using sentinel for masterId "
                        + redisURI.getSentinelMasterId());
            }

        } else {
            redisAddress = redisURI.getResolvedAddress();
        }
        return redisAddress;
    }

    private SocketAddress lookupRedis(String sentinelMasterId) throws InterruptedException, TimeoutException,
            ExecutionException {
        RedisSentinelAsyncConnection<String, String> connection = connectSentinelAsync();
        try {
            return connection.getMasterAddrByName(sentinelMasterId).get(timeout, unit);
        } finally {
            connection.close();
        }
    }

    protected Utf8StringCodec newStringStringCodec() {
        return new Utf8StringCodec();
    }

}
