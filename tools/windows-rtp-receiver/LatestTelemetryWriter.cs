// Console/Tee-Object may block. Never let telemetry block UDP reception.
internal sealed class LatestTelemetryWriter : IDisposable
{
    private readonly object _gate = new();
    private readonly AutoResetEvent _ready = new(false);
    private readonly Action<string> _write;
    private readonly Thread _worker;
    private string? _pending;
    private bool _stopped;
    private long _replaced;
    public long Replaced { get { lock (_gate) return _replaced; } }
    public LatestTelemetryWriter(Action<string>? write = null)
    {
        _write = write ?? Console.WriteLine;
        _worker = new Thread(Run) { IsBackground = true, Name = "camsure-telemetry" };
        _worker.Start();
    }
    public void Publish(string report)
    {
        lock (_gate)
        {
            if (_stopped) return;
            if (_pending is not null) _replaced++;
            _pending = report; // one pending snapshot; old telemetry is expendable
            _ready.Set();
        }
    }
    private void Run()
    {
        while (true)
        {
            _ready.WaitOne();
            string? report;
            lock (_gate) { report = _pending; _pending = null; if (_stopped && report is null) return; }
            try { if (report is not null) _write(report); }
            catch (IOException) { return; }
            lock (_gate) { if (_stopped && _pending is null) return; }
        }
    }
    public void Dispose()
    {
        lock (_gate) { if (_stopped) return; _stopped = true; _ready.Set(); }
        // An externally blocked console must not prevent receiver shutdown.
        if (_worker.Join(2000)) _ready.Dispose();
    }
}
