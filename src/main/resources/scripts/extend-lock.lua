-- Extend a distributed lock's TTL, but only if we still own it.
--
-- KEYS[1] = lock key
-- ARGV[1] = the token written when the lock was acquired
-- ARGV[2] = new TTL in milliseconds
--
-- The GET-compare and the PEXPIRE must not be interleaved: if the lock expired and
-- was re-acquired by another request between them, a bare PEXPIRE would hand that
-- request's lock a fresh TTL on our behalf. A script runs atomically, so the key can
-- only be extended while the stored token is still ours.
--
-- Returns 1 if the lock was ours and its TTL was pushed out, 0 otherwise.

if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('pexpire', KEYS[1], ARGV[2])
else
    return 0
end
