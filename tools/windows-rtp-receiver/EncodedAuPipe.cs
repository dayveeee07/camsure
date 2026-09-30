using System.Buffers.Binary;
using System.Diagnostics;
using System.IO.Pipes;

// Local IPC adapter, not a network protocol. Decoder sees only the AU model.
internal sealed class EncodedAuPipe : IEncodedVideoSink, IDisposable
{
    private readonly object _gate = new();
    private readonly Queue<EncodedVideoAccessUnit> _queue = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly Task _worker;
    private bool _recover = true;
    private int _bytes;
    private long _drops, _written, _errors, _maxAge, _maxSubmitUs;
    private int _maxDepth;
    private const int MaxBytes = 4 * 1024 * 1024;
    private const int MaxDepth = 4;
    private const int MaxAgeMs = 100;

    public EncodedAuPipe(string name)
    {
        if (name.Length is < 1 or > 64 || name.Any(c => !char.IsAsciiLetterOrDigit(c) && c != '-'))
            throw new ArgumentException("Pipe name must contain 1-64 ASCII letters, digits or hyphens.");
        _worker = Task.Run(() => Run(name));
    }

    public void Submit(EncodedVideoAccessUnit unit)
    {
        lock (_gate)
        {
            if (_stop.IsCancellationRequested) return;
            if (unit.Codec != VideoCodec.H264 || unit.AnnexB.Length is < 1 or > 2 * 1024 * 1024 || unit.CodecConfiguration.Length > 65536)
            { _drops++; Flush(); return; }
            if (_queue.TryPeek(out var queued)) _maxAge = Math.Max(_maxAge, Age(queued));
            if (unit.Discontinuity || _queue.Count >= MaxDepth || _bytes + unit.AnnexB.Length + unit.CodecConfiguration.Length > MaxBytes ||
                (_queue.TryPeek(out var oldest) && Age(oldest) > MaxAgeMs))
                Flush();
            if (_recover)
            {
                if (!unit.Keyframe || unit.CodecConfiguration.Length == 0) { _drops++; return; }
                unit = unit with { Discontinuity = true };
                _recover = false;
            }
            _queue.Enqueue(unit);
            _bytes += unit.AnnexB.Length + unit.CodecConfiguration.Length;
            _maxDepth = Math.Max(_maxDepth, _queue.Count);
        }
    }

    private void Flush()
    {
        _drops += _queue.Count;
        _queue.Clear(); _bytes = 0; _recover = true;
    }
    private static long Age(EncodedVideoAccessUnit unit) =>
        (long)Stopwatch.GetElapsedTime(unit.ReconstructedAtTicks).TotalMilliseconds;

    private async Task Run(string name)
    {
        while (!_stop.IsCancellationRequested)
        {
            try
            {
                using var pipe = new NamedPipeClientStream(".", name, PipeDirection.Out, PipeOptions.Asynchronous);
                await pipe.ConnectAsync(250, _stop.Token);
                lock (_gate) Flush(); // a new decoder needs configuration + IDR
                while (!_stop.IsCancellationRequested)
                {
                    EncodedVideoAccessUnit? unit = null;
                    lock (_gate)
                    {
                        if (_queue.TryDequeue(out unit))
                        {
                            _bytes -= unit.AnnexB.Length + unit.CodecConfiguration.Length;
                            _maxAge = Math.Max(_maxAge, Age(unit));
                            if (Age(unit) > MaxAgeMs) { _drops++; Flush(); unit = null; }
                        }
                    }
                    if (unit is null) { await Task.Delay(2, _stop.Token); continue; }
                    // v1 little endian: magic, version, data/config lengths, flags,
                    // session, exact signed PTS microseconds, reconstruction QPC.
                    var header = new byte[48];
                    BinaryPrimitives.WriteUInt32LittleEndian(header, 0x55415343);
                    BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(4), 1);
                    BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(8), unit.AnnexB.Length);
                    BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(12), unit.CodecConfiguration.Length);
                    BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(16), (unit.Keyframe ? 1u : 0u) | (unit.Discontinuity ? 2u : 0u));
                    BinaryPrimitives.WriteUInt64LittleEndian(header.AsSpan(24), unit.SessionId);
                    BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(32), unit.PresentationTimeUs);
                    BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(40), unit.ReconstructedAtTicks);
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token);
                    timeout.CancelAfter(MaxAgeMs);
                    await pipe.WriteAsync(header, timeout.Token);
                    await pipe.WriteAsync(unit.CodecConfiguration, timeout.Token);
                    await pipe.WriteAsync(unit.AnnexB, timeout.Token);
                    lock (_gate) { _written++; _maxSubmitUs = Math.Max(_maxSubmitUs, (long)Stopwatch.GetElapsedTime(unit.ReconstructedAtTicks).TotalMicroseconds); }
                }
            }
            catch (Exception error) when (error is IOException or TimeoutException or OperationCanceledException)
            {
                lock (_gate) { _errors++; Flush(); }
                if (!_stop.IsCancellationRequested)
                    try { await Task.Delay(100, _stop.Token); } catch (OperationCanceledException) { }
            }
        }
    }

    public string Report()
    {
        lock (_gate) return $"bridge written={_written}, dropped={_drops}, connections/errors={_errors}, AU-depth={_queue.Count}/{MaxDepth}, bytes={_bytes}/{MaxBytes}, current-age={(_queue.TryPeek(out var unit) ? Age(unit) : 0)}ms, max-depth={_maxDepth}, max-age={_maxAge}ms, reconstruction-to-write-max={_maxSubmitUs}us";
    }
    public void Dispose()
    {
        _stop.Cancel(); _worker.GetAwaiter().GetResult(); _stop.Dispose();
    }
}
