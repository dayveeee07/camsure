using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

internal static class Program
{
    private static int Main(string[] args)
    {
        if (args.SequenceEqual(new[] { "--self-test" })) return ReceiverSelfTest.Run();
        if (args.Length == 3 && args[0] == "--replay-test" && int.TryParse(args[2], out var testPort)) return ReplayTest.Run(args[1], testPort);
        if (!Options.TryParse(args, out var options, out var error))
        {
            Console.Error.WriteLine(error);
            Console.Error.WriteLine("Usage: dotnet run --project tools/windows-rtp-receiver -- [--bind 0.0.0.0] [--port 5004] [--duration-seconds N]");
            return 2;
        }

        using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)
        {
            ReceiveBufferSize = 1024 * 1024,
            ReceiveTimeout = 100
        };
        socket.Bind(new IPEndPoint(options.BindAddress, options.Port));
        Console.WriteLine("CamSure Phase 4 RTP/H.264 counter receiver");
        Console.WriteLine($"Listening on {options.BindAddress}:{options.Port} (IPv4 UDP)");
        Console.WriteLine($"Requested OS receive buffer: {socket.ReceiveBufferSize} bytes; application access-unit reassembly: 2 MiB / 500 ms");
        Console.WriteLine("Profile: RTP PT=96, H.264 packetization-mode=1, 90 kHz clock, RFC 8285 profile 0xBEDE");
        using var discovery = StartDiscoveryAdvertisement(options.Port);
        Console.WriteLine(options.PipeName is null ? "Counter-only mode; use --obs-pipe camsure-camera-1 for OBS." : $"Encoded AU bridge: {options.PipeName}");

        using var stop = new ManualResetEventSlim(false);
        Console.CancelKeyPress += (_, eventArgs) =>
        {
            eventArgs.Cancel = true;
            stop.Set();
        };

        using var bridge = options.PipeName is null ? null : new EncodedAuPipe(options.PipeName);
        var receiver = new RtpH264Receiver(bridge);
        var packet = new byte[RtpH264Receiver.MaxDatagramBytes];
        EndPoint remote = new IPEndPoint(IPAddress.Any, 0);
        var clock = Stopwatch.StartNew();
        var previousReport = TimeSpan.Zero;
        var start = clock.Elapsed;

        while (!stop.IsSet)
        {
            try
            {
                remote = new IPEndPoint(IPAddress.Any, 0);
                var length = socket.ReceiveFrom(packet, ref remote);
                receiver.Process(packet, length, Stopwatch.GetTimestamp());
            }
            catch (SocketException socketError) when (
                socketError.SocketErrorCode == SocketError.TimedOut ||
                socketError.SocketErrorCode == SocketError.WouldBlock)
            {
                // Periodically expire an access unit whose marker packet was lost.
            }

            receiver.ExpireStaleAccessUnit(Stopwatch.GetTimestamp());
            if (clock.Elapsed - previousReport >= TimeSpan.FromSeconds(1))
            {
                Console.WriteLine(receiver.FormatReport(clock.Elapsed - start));
                if (bridge is not null) Console.WriteLine(bridge.Report());
                previousReport = clock.Elapsed;
            }

            if (options.DurationSeconds > 0 && clock.Elapsed - start >= TimeSpan.FromSeconds(options.DurationSeconds))
                break;
        }

        Console.WriteLine("Final counters:");
        Console.WriteLine(receiver.FormatReport(clock.Elapsed - start));
                if (bridge is not null) Console.WriteLine(bridge.Report());
        return 0;
    }

    private static DnsSdReceiverAdvertisement? StartDiscoveryAdvertisement(int mediaPort)
    {
        try
        {
            var advertisement = DnsSdReceiverAdvertisement.Start(mediaPort);
            Console.WriteLine($"DNS-SD: advertising '{advertisement.InstanceName}' as {DnsSdReceiverAdvertisement.ServiceType}");
            Console.WriteLine($"DNS-SD IPv4 interfaces: {advertisement.AdvertisedAddresses}; media endpoint UDP {mediaPort}; mDNS UDP 5353");
            Console.WriteLine("If discovery is blocked, allow this receiver on the Windows Private network and check that AP multicast/client isolation is disabled.");
            return advertisement;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine("DNS-SD advertisement unavailable; fixed-address RTP remains available: " + error.Message);
            return null;
        }
    }

    private sealed record Options(IPAddress BindAddress, int Port, int DurationSeconds, string? PipeName)
    {
        public static bool TryParse(string[] args, out Options options, out string error)
        {
            var bind = IPAddress.Any;
            var port = 5004;
            var duration = 0;
            string? pipeName = null;
            for (var index = 0; index < args.Length; index++)
            {
                var value = index + 1 < args.Length ? args[index + 1] : null;
                switch (args[index])
                {
                    case "--obs-pipe" when value is not null:
                        pipeName = value; index++; break;
                    case "--bind" when value is not null && IPAddress.TryParse(value, out var address) && address.AddressFamily == AddressFamily.InterNetwork:
                        bind = address;
                        index++;
                        break;
                    case "--port" when value is not null && int.TryParse(value, out var parsedPort) && parsedPort is >= 1 and <= 65535:
                        port = parsedPort;
                        index++;
                        break;
                    case "--duration-seconds" when value is not null && int.TryParse(value, out var parsedDuration) && parsedDuration >= 0:
                        duration = parsedDuration;
                        index++;
                        break;
                    default:
                        options = new Options(bind, port, duration, pipeName);
                        error = $"Invalid or incomplete argument near '{args[index]}'.";
                        return false;
                }
            }

            options = new Options(bind, port, duration, pipeName);
            error = string.Empty;
            return true;
        }
    }
}

internal sealed class RtpH264Receiver
{
    public const int MaxDatagramBytes = 65_507;
    private const int MaxAcceptedDatagramBytes = 1_200;
    private const int MaxAccessUnitBytes = 2 * 1024 * 1024;
    private const int MaxAccessUnitAgeMs = 500;
    private const int PayloadType = 96;
    private const int H264ClockHz = 90_000;
    private const int ExtensionProfileOneByte = 0xbede;
    private const int NalFuA = 28;
    private const int NalStapA = 24;
    private const int NalIdr = 5;
    private const int NalSps = 7;
    private const int NalPps = 8;
    private const int ExtensionFlagKeyFrame = 0x01;
    private const int ExtensionFlagCodecConfig = 0x02;

    private readonly IEncodedVideoSink? _sink;
    private bool _discontinuity = true;
    private ulong _session = 1;
    private byte[] _sps = [], _pps = [];
    public RtpH264Receiver(IEncodedVideoSink? sink = null) { _sink = sink; }
    private byte[] _buffer = new byte[MaxAccessUnitBytes];
    private long _datagrams;
    private long _validRtpPackets;
    private long _receivedBytes;
    private long _wrongPayloadType;
    private long _oversizedDatagrams;
    private long _malformedPackets;
    private long _unsupportedPayloads;
    private long _packetGaps;
    private long _outOfOrderPackets;
    private long _incompleteAccessUnits;
    private long _completeAccessUnits;
    private long _completeKeyframes;
    private long _accessUnitsWithCodecConfig;
    private long _accessUnitsMissingSpsPps;
    private long _timestampMapMismatches;
    private long _nonMonotonicSourcePts;
    private long _keyframeFlagMismatches;
    private long _malformedFuA;
    private long _oversizedAccessUnits;
    private long _staleAccessUnits;
    private long _streamRestarts;
    private long _highWaterAccessUnitBytes;
    private long _highWaterAccessUnitAgeMs;
    private uint? _ssrc;
    private ushort? _lastSequence;
    private bool _hasRtpPtsOrigin;
    private long _rtpPtsOriginUs;
    private uint _rtpTimestampOrigin;
    private bool _accessUnitActive;
    private bool _accessUnitIncomplete;
    private bool _accessUnitOversized;
    private bool _fuActive;
    private int _accessUnitLength;
    private uint _accessUnitRtpTimestamp;
    private long? _accessUnitSourcePtsUs;
    private uint? _accessUnitCodecFlags;
    private byte? _accessUnitExtensionFlags;
    private long _accessUnitStartedAtTicks;
    private bool _accessUnitHasIdr;
    private bool _accessUnitHasSps;
    private bool _accessUnitHasPps;
    private long? _lastCompletedPtsUs;

    public void Process(byte[] packet, int length, long nowTicks)
    {
        _datagrams++;
        _receivedBytes += length;
        if (length > MaxAcceptedDatagramBytes)
        {
            _oversizedDatagrams++;
            return;
        }
        if (length < 12 || (packet[0] >> 6) != 2)
        {
            _malformedPackets++;
            return;
        }

        var marker = (packet[1] & 0x80) != 0;
        var payloadType = packet[1] & 0x7f;
        if (payloadType != PayloadType)
        {
            _wrongPayloadType++;
            return;
        }

        var sequence = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(2, 2));
        var rtpTimestamp = BinaryPrimitives.ReadUInt32BigEndian(packet.AsSpan(4, 4));
        var ssrc = BinaryPrimitives.ReadUInt32BigEndian(packet.AsSpan(8, 4));
        if (_ssrc is not null && _ssrc != ssrc) StartNewStream();
        _ssrc = ssrc;

        var headerLength = 12 + (packet[0] & 0x0f) * 4;
        if (headerLength > length)
        {
            _malformedPackets++;
            return;
        }

        long? sourcePtsUs = null;
        uint? codecFlags = null;
        byte? unitFlags = null;
        if ((packet[0] & 0x10) != 0)
        {
            if (!TryReadExtensions(packet, length, ref headerLength, out sourcePtsUs, out codecFlags, out unitFlags))
            {
                _malformedPackets++;
                return;
            }
        }

        var payloadEnd = length;
        if ((packet[0] & 0x20) != 0)
        {
            var padding = packet[length - 1];
            if (padding == 0 || padding > payloadEnd - headerLength)
            {
                _malformedPackets++;
                return;
            }
            payloadEnd -= padding;
        }
        if (payloadEnd <= headerLength)
        {
            _malformedPackets++;
            return;
        }
        _validRtpPackets++;

        var sequenceGapDetected = false;
        if (_lastSequence is not null)
        {
            var expected = unchecked((ushort)(_lastSequence.Value + 1));
            if (sequence != expected)
            {
                var forwardDistance = unchecked((ushort)(sequence - expected));
                if (forwardDistance < 0x8000)
                {
                    _packetGaps += forwardDistance;
                    _discontinuity = true;
                    sequenceGapDetected = true;
                    if (_accessUnitActive && _accessUnitRtpTimestamp == rtpTimestamp)
                    {
                        _accessUnitIncomplete = true;
                        _fuActive = false;
                    }
                }
                else
                {
                    _outOfOrderPackets++;
                    _discontinuity = true;
                    if (_accessUnitActive && _accessUnitRtpTimestamp == rtpTimestamp)
                    {
                        _accessUnitIncomplete = true;
                        _fuActive = false;
                    }
                    return;
                }
            }
        }
        _lastSequence = sequence;

        if (sourcePtsUs is not null) ValidateTimestampMap(rtpTimestamp, sourcePtsUs.Value);
        if (!_accessUnitActive)
        {
            StartAccessUnit(rtpTimestamp, sourcePtsUs, codecFlags, unitFlags, nowTicks);
            if (sequenceGapDetected) _accessUnitIncomplete = true;
        }
        else if (_accessUnitRtpTimestamp != rtpTimestamp)
        {
            FinishAccessUnit(marker: false);
            StartAccessUnit(rtpTimestamp, sourcePtsUs, codecFlags, unitFlags, nowTicks);
            if (sequenceGapDetected) _accessUnitIncomplete = true;
        }
        else
        {
            if (sourcePtsUs != _accessUnitSourcePtsUs || codecFlags != _accessUnitCodecFlags || unitFlags != _accessUnitExtensionFlags)
            {
                _malformedPackets++;
                _accessUnitIncomplete = true;
            }
        }

        var payload = packet.AsSpan(headerLength, payloadEnd - headerLength);
        ConsumeH264Payload(payload);
        if (marker) FinishAccessUnit(marker: true);
        UpdateHighWater(nowTicks);
    }

    public void ExpireStaleAccessUnit(long nowTicks)
    {
        if (!_accessUnitActive) return;
        var ageMs = TicksToMilliseconds(nowTicks - _accessUnitStartedAtTicks);
        _highWaterAccessUnitAgeMs = Math.Max(_highWaterAccessUnitAgeMs, ageMs);
        if (ageMs > MaxAccessUnitAgeMs)
        {
            _staleAccessUnits++;
            FinishAccessUnit(marker: false);
        }
    }

    public string FormatReport(TimeSpan elapsed) =>
        $"t={elapsed:hh\\:mm\\:ss} | packets={_validRtpPackets}/{_datagrams} ({_receivedBytes} bytes) | " +
        $"AUs complete={_completeAccessUnits}, IDR={_completeKeyframes}, incomplete={_incompleteAccessUnits} | " +
        $"gaps={_packetGaps}, out-of-order={_outOfOrderPackets}, bad-packets={_malformedPackets}, oversized-datagrams={_oversizedDatagrams}, unsupported={_unsupportedPayloads} | " +
        $"PTS-map-mismatch={_timestampMapMismatches}, nonmonotonic-PTS={_nonMonotonicSourcePts}, key-flag-mismatch={_keyframeFlagMismatches} | " +
        $"config-AUs={_accessUnitsWithCodecConfig}, AUs-without-SPS/PPS={_accessUnitsMissingSpsPps} | " +
        $"reassembly-depth={(_accessUnitActive ? 1 : 0)}/1, bytes={_accessUnitLength}/{MaxAccessUnitBytes}, age={CurrentAccessUnitAgeMs()} ms/{MaxAccessUnitAgeMs} ms, " +
        $"high-water={_highWaterAccessUnitBytes} bytes/{_highWaterAccessUnitAgeMs} ms | " +
        $"SSRC={_ssrc?.ToString() ?? "waiting"}, restarts={_streamRestarts}";

    private bool TryReadExtensions(
        byte[] packet,
        int packetLength,
        ref int headerLength,
        out long? sourcePtsUs,
        out uint? codecFlags,
        out byte? unitFlags)
    {
        sourcePtsUs = null;
        codecFlags = null;
        unitFlags = null;
        if (headerLength + 4 > packetLength) return false;
        var profile = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(headerLength, 2));
        var extensionWords = BinaryPrimitives.ReadUInt16BigEndian(packet.AsSpan(headerLength + 2, 2));
        var extensionStart = headerLength + 4;
        var extensionEnd = extensionStart + extensionWords * 4;
        if (extensionEnd > packetLength) return false;
        if (profile == ExtensionProfileOneByte)
        {
            var offset = extensionStart;
            while (offset < extensionEnd)
            {
                var elementHeader = packet[offset++];
                if (elementHeader == 0) continue;
                var id = elementHeader >> 4;
                if (id == 15) break;
                var elementLength = (elementHeader & 0x0f) + 1;
                if (offset + elementLength > extensionEnd) return false;
                switch (id)
                {
                    case 1 when elementLength == 8:
                        sourcePtsUs = BinaryPrimitives.ReadInt64BigEndian(packet.AsSpan(offset, 8));
                        break;
                    case 2 when elementLength == 4:
                        codecFlags = BinaryPrimitives.ReadUInt32BigEndian(packet.AsSpan(offset, 4));
                        break;
                    case 3 when elementLength == 1:
                        unitFlags = packet[offset];
                        break;
                }
                offset += elementLength;
            }
        }
        headerLength = extensionEnd;
        return true;
    }

    private void ValidateTimestampMap(uint rtpTimestamp, long sourcePtsUs)
    {
        if (!_hasRtpPtsOrigin)
        {
            _hasRtpPtsOrigin = true;
            _rtpPtsOriginUs = sourcePtsUs;
            _rtpTimestampOrigin = rtpTimestamp;
            return;
        }
        var deltaUs = sourcePtsUs - _rtpPtsOriginUs;
        var expectedTicks = (long)Math.Round(deltaUs * (double)H264ClockHz / 1_000_000.0, MidpointRounding.AwayFromZero);
        var expected = unchecked((uint)((long)_rtpTimestampOrigin + expectedTicks));
        if (expected != rtpTimestamp) _timestampMapMismatches++;
    }

    private void ConsumeH264Payload(ReadOnlySpan<byte> payload)
    {
        var type = payload[0] & 0x1f;
        if (type is >= 1 and <= 23)
        {
            if (_fuActive)
            {
                _malformedFuA++;
                _accessUnitIncomplete = true;
                _fuActive = false;
            }
            AddNal(payload);
            return;
        }

        if (type == NalStapA)
        {
            ConsumeStapA(payload);
            return;
        }

        if (type == NalFuA)
        {
            ConsumeFuA(payload);
            return;
        }

        _unsupportedPayloads++;
        _accessUnitIncomplete = true;
    }

    private void ConsumeStapA(ReadOnlySpan<byte> payload)
    {
        if (_fuActive)
        {
            _malformedFuA++;
            _accessUnitIncomplete = true;
            _fuActive = false;
        }
        var offset = 1;
        while (offset + 2 <= payload.Length)
        {
            var nalLength = BinaryPrimitives.ReadUInt16BigEndian(payload.Slice(offset, 2));
            offset += 2;
            if (nalLength == 0 || offset + nalLength > payload.Length)
            {
                _malformedPackets++;
                _accessUnitIncomplete = true;
                return;
            }
            AddNal(payload.Slice(offset, nalLength));
            offset += nalLength;
        }
        if (offset != payload.Length)
        {
            _malformedPackets++;
            _accessUnitIncomplete = true;
        }
    }

    private void ConsumeFuA(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < 3)
        {
            _malformedFuA++;
            _accessUnitIncomplete = true;
            _fuActive = false;
            return;
        }
        var indicator = payload[0];
        var header = payload[1];
        var start = (header & 0x80) != 0;
        var end = (header & 0x40) != 0;
        var nalType = header & 0x1f;
        if (start && end)
        {
            _malformedFuA++;
            _accessUnitIncomplete = true;
            _fuActive = false;
            return;
        }
        if (start)
        {
            if (_fuActive)
            {
                _malformedFuA++;
                _accessUnitIncomplete = true;
            }
            _fuActive = true;
            AppendStartCode();
            Span<byte> reconstructedHeader = stackalloc byte[1];
            reconstructedHeader[0] = (byte)((indicator & 0xe0) | nalType);
            AppendBytes(reconstructedHeader);
            TrackNalType(nalType);
        }
        else if (!_fuActive)
        {
            _malformedFuA++;
            _accessUnitIncomplete = true;
            return;
        }

        AppendBytes(payload[2..]);
        if (end) _fuActive = false;
    }

    private void AddNal(ReadOnlySpan<byte> nal)
    {
        if (nal.IsEmpty)
        {
            _malformedPackets++;
            _accessUnitIncomplete = true;
            return;
        }
        AppendStartCode();
        AppendBytes(nal);
        TrackNalType(nal[0] & 0x1f);
    }

    private void TrackNalType(int type)
    {
        if (type == NalIdr) _accessUnitHasIdr = true;
        if (type == NalSps) _accessUnitHasSps = true;
        if (type == NalPps) _accessUnitHasPps = true;
    }

    private void AppendStartCode()
    {
        ReadOnlySpan<byte> startCode = stackalloc byte[] { 0, 0, 0, 1 };
        AppendBytes(startCode);
    }

    private void AppendBytes(ReadOnlySpan<byte> bytes)
    {
        if (_accessUnitOversized) return;
        if (_accessUnitLength + bytes.Length > MaxAccessUnitBytes)
        {
            _oversizedAccessUnits++;
            _accessUnitOversized = true;
            _accessUnitIncomplete = true;
            return;
        }
        bytes.CopyTo(_buffer.AsSpan(_accessUnitLength));
        _accessUnitLength += bytes.Length;
        _highWaterAccessUnitBytes = Math.Max(_highWaterAccessUnitBytes, _accessUnitLength);
    }

    private void StartAccessUnit(
        uint rtpTimestamp,
        long? sourcePtsUs,
        uint? codecFlags,
        byte? unitFlags,
        long nowTicks)
    {
        _accessUnitActive = true;
        _accessUnitIncomplete = false;
        _accessUnitOversized = false;
        _fuActive = false;
        _accessUnitLength = 0;
        _accessUnitRtpTimestamp = rtpTimestamp;
        _accessUnitSourcePtsUs = sourcePtsUs;
        _accessUnitCodecFlags = codecFlags;
        _accessUnitExtensionFlags = unitFlags;
        _accessUnitStartedAtTicks = nowTicks;
        _accessUnitHasIdr = false;
        _accessUnitHasSps = false;
        _accessUnitHasPps = false;
    }

    private void FinishAccessUnit(bool marker)
    {
        if (!_accessUnitActive) return;
        var complete = marker && !_accessUnitIncomplete && !_accessUnitOversized && !_fuActive && _accessUnitLength > 0;
        if (complete)
        {
            _completeAccessUnits++;
            PublishAccessUnit();
            if (_accessUnitHasIdr) _completeKeyframes++;
            var configExtension = _accessUnitExtensionFlags is not null &&
                (_accessUnitExtensionFlags.Value & ExtensionFlagCodecConfig) != 0;
            if (configExtension && _accessUnitHasSps && _accessUnitHasPps) _accessUnitsWithCodecConfig++;
            if (!_accessUnitHasSps || !_accessUnitHasPps) _accessUnitsMissingSpsPps++;
            var keyframeExtension = _accessUnitExtensionFlags is not null &&
                (_accessUnitExtensionFlags.Value & ExtensionFlagKeyFrame) != 0;
            if (keyframeExtension != _accessUnitHasIdr) _keyframeFlagMismatches++;
            if (_accessUnitSourcePtsUs is not null)
            {
                if (_lastCompletedPtsUs is not null && _accessUnitSourcePtsUs <= _lastCompletedPtsUs)
                    _nonMonotonicSourcePts++;
                _lastCompletedPtsUs = _accessUnitSourcePtsUs;
            }
        }
        else
        {
            _incompleteAccessUnits++;
            _discontinuity = true;
        }

        _accessUnitActive = false;
        _accessUnitLength = 0;
        _accessUnitIncomplete = false;
        _accessUnitOversized = false;
        _fuActive = false;
    }

    private void PublishAccessUnit()
    {
        // Reassembly writes four-byte start codes, including reconstructed FU-A NALs.
        for (var start = 0; start + 4 < _accessUnitLength;)
        {
            var end = start + 4;
            while (end + 3 < _accessUnitLength && !(_buffer[end] == 0 && _buffer[end + 1] == 0 && _buffer[end + 2] == 0 && _buffer[end + 3] == 1)) end++;
            if (end + 3 >= _accessUnitLength) end = _accessUnitLength;
            var type = _buffer[start + 4] & 31;
            if (type is NalSps or NalPps)
            {
                var config = _buffer.AsSpan(start, end - start).ToArray();
                var previous = type == NalSps ? _sps : _pps;
                if (!previous.AsSpan().SequenceEqual(config)) _discontinuity = true;
                if (type == NalSps) _sps = config; else _pps = config;
            }
            start = end;
        }
        if (_accessUnitSourcePtsUs is not long pts || pts < 0 ||
            (_lastCompletedPtsUs is long last && pts <= last))
        { _discontinuity = true; return; }
        var configuration = _sps.Length > 0 && _pps.Length > 0 ? _sps.Concat(_pps).ToArray() : [];
        _sink?.Submit(new EncodedVideoAccessUnit(_session, _buffer.AsSpan(0, _accessUnitLength).ToArray(),
            pts, _accessUnitHasIdr, configuration, _discontinuity, Stopwatch.GetTimestamp()));
        _discontinuity = false;
    }

    private void StartNewStream()
    {
        if (_accessUnitActive) FinishAccessUnit(marker: false);
        _streamRestarts++;
        _session++; _discontinuity = true; _sps = []; _pps = [];
        _lastSequence = null;
        _hasRtpPtsOrigin = false;
        _lastCompletedPtsUs = null;
    }

    private long CurrentAccessUnitAgeMs() => _accessUnitActive
        ? TicksToMilliseconds(Stopwatch.GetTimestamp() - _accessUnitStartedAtTicks)
        : 0;

    private void UpdateHighWater(long nowTicks)
    {
        if (_accessUnitActive)
            _highWaterAccessUnitAgeMs = Math.Max(_highWaterAccessUnitAgeMs, TicksToMilliseconds(nowTicks - _accessUnitStartedAtTicks));
    }

    private static long TicksToMilliseconds(long ticks) =>
        ticks <= 0 ? 0 : (long)(ticks * 1000.0 / Stopwatch.Frequency);
}
