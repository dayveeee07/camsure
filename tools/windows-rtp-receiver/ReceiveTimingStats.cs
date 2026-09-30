using System.Diagnostics;

// Application timing only: receive wait includes both network idle time and scheduling.
internal sealed class ReceiveTimingStats
{
    private long _returnedAt, _bucketStart, _bucketBytes, _burstMax;
    private long _serviceMax, _waitMax;
    public void BeforeReceive(long now)
    {
        if (_returnedAt != 0) _serviceMax = Math.Max(_serviceMax, now - _returnedAt);
    }
    public void Received(long startedAt, long returnedAt, int bytes)
    {
        _waitMax = Math.Max(_waitMax, returnedAt - startedAt);
        _returnedAt = returnedAt;
        if (_bucketStart == 0 || returnedAt - _bucketStart >= Stopwatch.Frequency / 1000)
        { _bucketStart = returnedAt; _bucketBytes = 0; }
        _bucketBytes += bytes;
        _burstMax = Math.Max(_burstMax, _bucketBytes);
    }
    public void Idle() => _returnedAt = 0;
    public string Report() => $"receive-service-max={_serviceMax * 1000.0 / Stopwatch.Frequency:F3}ms, receive-wait-max={_waitMax * 1000.0 / Stopwatch.Frequency:F3}ms, receive-bucket-1ms-max={_burstMax}bytes";
}
