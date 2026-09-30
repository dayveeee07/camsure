using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

/// <summary>Minimal IPv4 mDNS/DNS-SD advertiser for the local CamSure RTP receiver.</summary>
internal sealed class DnsSdReceiverAdvertisement : IDisposable
{
    public const string ServiceType = "_camsure-rtp._udp.local.";
    private const int MdnsPort = 5353;
    private const uint RecordTtlSeconds = 120;
    private static readonly IPAddress MulticastAddress = IPAddress.Parse("224.0.0.251");

    private readonly Socket _socket;
    private readonly string _instanceName;
    private readonly string _instanceFqdn;
    private readonly string _hostFqdn;
    private readonly int _mediaPort;
    private readonly Thread _worker;
    private List<IPAddress> _interfaceAddresses;
    private volatile bool _stopping;

    private DnsSdReceiverAdvertisement(Socket socket, int mediaPort, List<IPAddress> addresses)
    {
        _socket = socket;
        _mediaPort = mediaPort;
        _interfaceAddresses = addresses;
        var machineName = SanitizeHostLabel(Environment.MachineName);
        _instanceName = "CamSure Receiver " + Environment.MachineName;
        _instanceFqdn = _instanceName + "." + ServiceType;
        _hostFqdn = machineName + ".local.";
        _worker = new Thread(WorkerLoop)
        {
            IsBackground = true,
            Name = "camsure-dnssd-advertiser"
        };
        _worker.Start();
    }

    public static DnsSdReceiverAdvertisement Start(int mediaPort)
    {
        var addresses = GetLocalIpv4Addresses();
        if (addresses.Count == 0)
            throw new InvalidOperationException("No active local IPv4 interface is available for DNS-SD advertisement.");

        var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)
        {
            ExclusiveAddressUse = false,
            MulticastLoopback = true,
            ReceiveTimeout = 500
        };
        socket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        socket.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 255);
        socket.Bind(new IPEndPoint(IPAddress.Any, MdnsPort));

        var joined = new List<IPAddress>();
        foreach (var address in addresses)
        {
            try
            {
                socket.SetSocketOption(
                    SocketOptionLevel.IP,
                    SocketOptionName.AddMembership,
                    new MulticastOption(MulticastAddress, address));
                joined.Add(address);
            }
            catch (SocketException)
            {
                // Continue on interfaces where multicast is available.
            }
        }

        if (joined.Count == 0)
        {
            socket.Dispose();
            throw new InvalidOperationException("Could not join the mDNS multicast group on an active IPv4 interface.");
        }

        return new DnsSdReceiverAdvertisement(socket, mediaPort, joined);
    }

    public string InstanceName => _instanceName;

    public string AdvertisedAddresses => string.Join(", ", _interfaceAddresses.Select(address => address.ToString()));

    public void Dispose()
    {
        _stopping = true;
        if (_worker.IsAlive && Thread.CurrentThread != _worker)
            _worker.Join(TimeSpan.FromSeconds(2));
        _socket.Dispose();
    }

    private void WorkerLoop()
    {
        var nextAnnouncement = DateTime.UtcNow;
        var nextInterfaceRefresh = DateTime.UtcNow.AddSeconds(10);
        var packet = new byte[9_000];
        try
        {
            while (!_stopping)
            {
                var now = DateTime.UtcNow;
                if (now >= nextInterfaceRefresh)
                {
                    RefreshInterfaces();
                    nextInterfaceRefresh = now.AddSeconds(10);
                }
                if (now >= nextAnnouncement)
                {
                    Announce(RecordTtlSeconds);
                    nextAnnouncement = now.AddSeconds(60);
                }

                try
                {
                    EndPoint remote = new IPEndPoint(IPAddress.Any, 0);
                    var length = _socket.ReceiveFrom(packet, ref remote);
                    if (remote is IPEndPoint sender && IsQuestion(packet, length))
                        Respond(packet.AsSpan(0, length), sender);
                }
                catch (SocketException error) when (
                    error.SocketErrorCode is SocketError.TimedOut or SocketError.WouldBlock)
                {
                    // Periodically refresh interfaces and re-announce for reconnects.
                }
                catch (ObjectDisposedException)
                {
                    break;
                }
            }
        }
        catch (Exception error)
        {
            Console.Error.WriteLine("DNS-SD advertiser stopped: " + error.Message);
        }
        finally
        {
            try { Announce(0); } catch (Exception) { }
        }
    }

    private void RefreshInterfaces()
    {
        var refreshed = GetLocalIpv4Addresses();
        var removed = _interfaceAddresses.Except(refreshed).ToList();
        var added = refreshed.Except(_interfaceAddresses).ToList();
        if (removed.Count == 0 && added.Count == 0) return;

        foreach (var address in removed)
        {
            try { SendAdvertisement(address, 0); } catch (SocketException) { }
            try
            {
                _socket.SetSocketOption(
                    SocketOptionLevel.IP,
                    SocketOptionName.DropMembership,
                    new MulticastOption(MulticastAddress, address));
            }
            catch (SocketException) { }
        }
        foreach (var address in added)
        {
            try
            {
                _socket.SetSocketOption(
                    SocketOptionLevel.IP,
                    SocketOptionName.AddMembership,
                    new MulticastOption(MulticastAddress, address));
            }
            catch (SocketException) { }
        }

        _interfaceAddresses = refreshed.Where(address =>
        {
            try
            {
                _socket.SetSocketOption(
                    SocketOptionLevel.IP,
                    SocketOptionName.MulticastInterface,
                    address.GetAddressBytes());
                return true;
            }
            catch (SocketException)
            {
                return false;
            }
        }).ToList();
        if (_interfaceAddresses.Count > 0)
        {
            Console.WriteLine("DNS-SD network interfaces changed; advertising on " + AdvertisedAddresses);
            Announce(RecordTtlSeconds);
        }
    }

    private void Announce(uint ttl)
    {
        foreach (var address in _interfaceAddresses.ToArray())
        {
            try { SendAdvertisement(address, ttl); }
            catch (SocketException error)
            {
                if (!_stopping) Console.Error.WriteLine("DNS-SD announcement failed on " + address + ": " + error.Message);
            }
        }
    }

    private void SendAdvertisement(IPAddress address, uint ttl)
    {
        SetOutgoingInterface(address);
        var records = BuildRecords(address, ttl);
        var packet = BuildMessage(0, 0x8400, records, Array.Empty<ResourceRecord>());
        _socket.SendTo(packet, new IPEndPoint(MulticastAddress, MdnsPort));
    }

    private void Respond(ReadOnlySpan<byte> query, IPEndPoint sender)
    {
        if (!TryReadQuestions(query, out var id, out var questions)) return;
        if (questions.Any(question => NameEquals(question.Name, ServiceType)))
            Console.WriteLine("DNS-SD browse query from " + sender.Address + ":" + sender.Port + ".");
        var answers = new Dictionary<string, ResourceRecord>(StringComparer.OrdinalIgnoreCase);
        var additionals = new Dictionary<string, ResourceRecord>(StringComparer.OrdinalIgnoreCase);
        var addresses = _interfaceAddresses.ToArray();

        foreach (var address in addresses)
        {
            var records = BuildRecords(address, RecordTtlSeconds);
            var ptr = records.Single(record => record.Type == RecordTypePtr);
            var srv = records.Single(record => record.Type == RecordTypeSrv);
            var txt = records.Single(record => record.Type == RecordTypeTxt);
            var host = records.Single(record => record.Type == RecordTypeA);

            foreach (var question in questions)
            {
                if (question.RecordClass is not (RecordClassIn or RecordClassAny)) continue;
                if (NameEquals(question.Name, ServiceType) && question.Type is RecordTypePtr or RecordTypeAny)
                {
                    Add(answers, ptr);
                    Add(additionals, srv);
                    Add(additionals, txt);
                    Add(additionals, host);
                }
                else if (NameEquals(question.Name, _instanceFqdn))
                {
                    if (question.Type is RecordTypeSrv or RecordTypeAny) Add(answers, srv);
                    if (question.Type is RecordTypeTxt or RecordTypeAny) Add(answers, txt);
                    if (question.Type is RecordTypeSrv or RecordTypeTxt or RecordTypeAny) Add(additionals, host);
                }
                else if (NameEquals(question.Name, _hostFqdn) && question.Type is RecordTypeA or RecordTypeAny)
                {
                    Add(answers, host);
                }
            }
        }

        if (answers.Count == 0) return;
        var answerPacket = BuildMessage(id, 0x8400, answers.Values.ToArray(), additionals.Values.ToArray());
        Console.WriteLine("DNS-SD answered " + answers.Count + " record(s) plus " + additionals.Count +
            " additional record(s) to " + sender.Address + ":" + sender.Port + ".");
        var wantsUnicast = questions.Any(question => question.UnicastResponse);
        if (wantsUnicast)
        {
            _socket.SendTo(answerPacket, sender);
            return;
        }

        foreach (var address in _interfaceAddresses.ToArray())
        {
            try
            {
                SetOutgoingInterface(address);
                _socket.SendTo(answerPacket, new IPEndPoint(MulticastAddress, MdnsPort));
            }
            catch (SocketException) { }
        }
    }

    private List<ResourceRecord> BuildRecords(IPAddress address, uint ttl)
    {
        var ptrData = EncodeName(_instanceFqdn);
        var srvData = new List<byte>(6 + EncodeName(_hostFqdn).Length);
        WriteUInt16(srvData, 0); // priority
        WriteUInt16(srvData, 0); // weight
        WriteUInt16(srvData, _mediaPort);
        srvData.AddRange(EncodeName(_hostFqdn));
        var txtData = EncodeTxt("version=1", "protocol=rtp-h264", "transport=udp");
        return new List<ResourceRecord>
        {
            new(ServiceType, RecordTypePtr, RecordClassIn, ttl, ptrData),
            new(_instanceFqdn, RecordTypeSrv, RecordClassIn | RecordCacheFlush, ttl, srvData.ToArray()),
            new(_instanceFqdn, RecordTypeTxt, RecordClassIn | RecordCacheFlush, ttl, txtData),
            new(_hostFqdn, RecordTypeA, RecordClassIn | RecordCacheFlush, ttl, address.GetAddressBytes())
        };
    }

    private static byte[] BuildMessage(ushort id, ushort flags, IReadOnlyList<ResourceRecord> answers, IReadOnlyList<ResourceRecord> additionals)
    {
        var bytes = new List<byte>(512);
        WriteUInt16(bytes, id);
        WriteUInt16(bytes, flags);
        WriteUInt16(bytes, 0); // questions
        WriteUInt16(bytes, answers.Count);
        WriteUInt16(bytes, 0); // authority
        WriteUInt16(bytes, additionals.Count);
        foreach (var record in answers) WriteRecord(bytes, record);
        foreach (var record in additionals) WriteRecord(bytes, record);
        return bytes.ToArray();
    }

    private static void WriteRecord(List<byte> target, ResourceRecord record)
    {
        target.AddRange(EncodeName(record.Name));
        WriteUInt16(target, record.Type);
        WriteUInt16(target, record.Class);
        WriteUInt32(target, record.Ttl);
        WriteUInt16(target, record.Data.Length);
        target.AddRange(record.Data);
    }

    private static byte[] EncodeTxt(params string[] values)
    {
        var result = new List<byte>();
        foreach (var value in values)
        {
            var bytes = Encoding.UTF8.GetBytes(value);
            if (bytes.Length > byte.MaxValue) throw new InvalidOperationException("DNS-SD TXT value exceeds 255 bytes.");
            result.Add((byte)bytes.Length);
            result.AddRange(bytes);
        }
        return result.ToArray();
    }

    private static byte[] EncodeName(string name)
    {
        var result = new List<byte>();
        foreach (var label in name.TrimEnd('.').Split('.'))
        {
            var bytes = Encoding.UTF8.GetBytes(label);
            if (bytes.Length is 0 or > 63) throw new InvalidOperationException("Invalid DNS label in '" + name + "'.");
            result.Add((byte)bytes.Length);
            result.AddRange(bytes);
        }
        result.Add(0);
        return result.ToArray();
    }

    private static bool TryReadQuestions(ReadOnlySpan<byte> packet, out ushort id, out List<Question> questions)
    {
        id = 0;
        questions = new List<Question>();
        if (packet.Length < 12) return false;
        id = ReadUInt16(packet, 0);
        var flags = ReadUInt16(packet, 2);
        if ((flags & 0x8000) != 0) return false; // Ignore mDNS responses.
        var count = ReadUInt16(packet, 4);
        var offset = 12;
        try
        {
            for (var index = 0; index < count; index++)
            {
                var name = ReadName(packet, ref offset);
                if (offset + 4 > packet.Length) return false;
                var type = ReadUInt16(packet, offset);
                var rawClass = ReadUInt16(packet, offset + 2);
                offset += 4;
                questions.Add(new Question(name, type, (ushort)(rawClass & 0x7fff), (rawClass & 0x8000) != 0));
            }
        }
        catch (FormatException)
        {
            return false;
        }
        return questions.Count > 0;
    }

    private static string ReadName(ReadOnlySpan<byte> packet, ref int offset)
    {
        var labels = new List<string>();
        var cursor = offset;
        var jumped = false;
        var visited = new HashSet<int>();
        while (cursor < packet.Length)
        {
            var length = packet[cursor++];
            if (length == 0)
            {
                if (!jumped) offset = cursor;
                return labels.Count == 0 ? "." : string.Join('.', labels) + ".";
            }
            if ((length & 0xc0) == 0xc0)
            {
                if (cursor >= packet.Length) throw new FormatException("Truncated DNS compression pointer.");
                var pointer = ((length & 0x3f) << 8) | packet[cursor++];
                if (pointer >= packet.Length || !visited.Add(pointer)) throw new FormatException("Invalid DNS compression pointer.");
                if (!jumped) offset = cursor;
                cursor = pointer;
                jumped = true;
                continue;
            }
            if ((length & 0xc0) != 0 || cursor + length > packet.Length)
                throw new FormatException("Invalid DNS label.");
            labels.Add(Encoding.UTF8.GetString(packet.Slice(cursor, length)));
            cursor += length;
        }
        throw new FormatException("Unterminated DNS name.");
    }

    private static bool IsQuestion(byte[] packet, int length) =>
        length >= 12 && (ReadUInt16(packet, 2) & 0x8000) == 0 && ReadUInt16(packet, 4) > 0;

    private static void Add(Dictionary<string, ResourceRecord> records, ResourceRecord record) =>
        records.TryAdd(record.Name + "|" + record.Type + "|" + Convert.ToHexString(record.Data), record);

    private static bool NameEquals(string left, string right) =>
        string.Equals(left.TrimEnd('.'), right.TrimEnd('.'), StringComparison.OrdinalIgnoreCase);

    private void SetOutgoingInterface(IPAddress address) =>
        _socket.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, address.GetAddressBytes());

    private static List<IPAddress> GetLocalIpv4Addresses()
    {
        var candidates = new List<(IPAddress Address, bool HasGateway)>();
        foreach (var network in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (network.OperationalStatus != OperationalStatus.Up ||
                network.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                continue;
            try
            {
                var hasGateway = network.GetIPProperties().GatewayAddresses.Any(gateway =>
                    gateway.Address.AddressFamily == AddressFamily.InterNetwork && !gateway.Address.Equals(IPAddress.Any));
                foreach (var entry in network.GetIPProperties().UnicastAddresses)
                {
                    var address = entry.Address;
                    var bytes = address.GetAddressBytes();
                    if (address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(address) ||
                        bytes[0] == 0 || bytes[0] == 127 || (bytes[0] == 169 && bytes[1] == 254) ||
                        bytes[0] is >= 224 and <= 239)
                        continue;
                    if (!candidates.Any(candidate => candidate.Address.Equals(address))) candidates.Add((address, hasGateway));
                }
            }
            catch (NetworkInformationException) { }
        }
        var defaultRouteAddresses = candidates.Where(candidate => candidate.HasGateway).Select(candidate => candidate.Address).ToList();
        return defaultRouteAddresses.Count > 0 ? defaultRouteAddresses : candidates.Select(candidate => candidate.Address).ToList();
    }

    private static string SanitizeHostLabel(string value)
    {
        var label = new string(value.Select(character =>
            char.IsAsciiLetterOrDigit(character) || character == '-' ? char.ToLowerInvariant(character) : '-').ToArray())
            .Trim('-');
        if (label.Length == 0) label = "windows-receiver";
        if (label.Length > 63) label = label[..63].TrimEnd('-');
        return label;
    }

    private static ushort ReadUInt16(ReadOnlySpan<byte> source, int offset) =>
        (ushort)((source[offset] << 8) | source[offset + 1]);

    private static void WriteUInt16(List<byte> target, int value)
    {
        target.Add((byte)(value >> 8));
        target.Add((byte)value);
    }

    private static void WriteUInt32(List<byte> target, uint value)
    {
        target.Add((byte)(value >> 24));
        target.Add((byte)(value >> 16));
        target.Add((byte)(value >> 8));
        target.Add((byte)value);
    }

    private sealed record Question(string Name, ushort Type, ushort RecordClass, bool UnicastResponse);
    private sealed record ResourceRecord(string Name, ushort Type, ushort Class, uint Ttl, byte[] Data);

    private const ushort RecordTypeA = 1;
    private const ushort RecordTypePtr = 12;
    private const ushort RecordTypeTxt = 16;
    private const ushort RecordTypeSrv = 33;
    private const ushort RecordTypeAny = 255;
    private const ushort RecordClassIn = 1;
    private const ushort RecordClassAny = 255;
    private const ushort RecordCacheFlush = 0x8000;
}
