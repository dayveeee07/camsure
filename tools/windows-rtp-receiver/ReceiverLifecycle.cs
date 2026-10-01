// Optional OBS process ownership; CLI Ctrl+C and timed runs retain their behavior.
internal sealed class ReceiverLifecycle : IDisposable
{
    private readonly EventWaitHandle? stop, ready;
    public ReceiverLifecycle(string? stopName, string? readyName)
    {
        if (stopName is null && readyName is null) return;
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("OBS receiver lifecycle requires Windows.");
        stop = EventWaitHandle.OpenExisting(stopName!);
        try { ready = EventWaitHandle.OpenExisting(readyName!); }
        catch { stop.Dispose(); throw; }
    }
    public bool StopRequested => stop?.WaitOne(0) == true;
    public void Ready() => ready?.Set();
    public void Dispose() { ready?.Dispose(); stop?.Dispose(); }
}
