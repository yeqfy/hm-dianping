	-- Lua脚本动态参数
	-- EVAL 'return redis.call('set', KEYS[1],  ARGV[1])' 1 key value

-- 锁的key
-- 	写死参数
-- local key = "lock:order:"
-- 	动态传参
--local key = KEYS[1]

-- 线程标识
-- local threadId = "kjdxbfiabfia-33"
--	动态传参
--local threadId = ARGV[1]

-- 从redis中获取锁的线程标识
-- local id = redis.call('get', KEYS)

-- 比较 线程标识 与 锁中标识 是否一致
if(redis.call('get', KEYS[1]) == ARGV[1]) then
	-- 释放锁 del key
	return redis.call('del',  KEYS[1])
end
return 0;