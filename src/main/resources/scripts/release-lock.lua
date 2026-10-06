-- Release a distributed lock, but only if we still own it.
--
-- KEYS[1] = lock key
-- ARGV[1] = the token written when the lock was acquired
--
-- A plain GET-then-DELETE from Java is not safe: the lock can expire between the
-- two calls and be re-acquired by another request, which this one would then
-- delete out from under it. Redis runs a script atomically, so the compare and
-- the delete cannot be interleaved.
--
-- Returns 1 if the lock was ours and got deleted, 0 otherwise.

if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
