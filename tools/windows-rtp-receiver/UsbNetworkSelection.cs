using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

// Explicit operator selection is required: adapter names alone do not prove USB.
internal sealed record UsbNetworkSelection(string AdapterId, string AdapterName, IPAddress Address, int PrefixLength)
{
    public static UsbNetworkSelection[] Enumerate() => NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType == NetworkInterfaceType.Ethernet)
        .SelectMany(n => n.GetIPProperties().UnicastAddresses
            .Where(a => Usable(a.Address))
            .Select(a => new UsbNetworkSelection(n.Id, n.Name, a.Address, a.PrefixLength))).ToArray();

    internal static bool Usable(IPAddress a) => a.AddressFamily == AddressFamily.InterNetwork &&
        !IPAddress.IsLoopback(a) && a.GetAddressBytes()[0] is > 0 and < 224 &&
        !a.Equals(IPAddress.Broadcast) && !(a.GetAddressBytes()[0] == 169 && a.GetAddressBytes()[1] == 254);

    internal static UsbNetworkSelection Select(IEnumerable<UsbNetworkSelection> candidates, string id, IPAddress address, IPAddress peer)
    {
        var inventory = candidates.ToArray();
        if (inventory.Count(c => c.Address.Equals(address)) != 1) throw new ArgumentException("Local address is absent or assigned to multiple adapters.");
        var matches = inventory.Where(c => c.AdapterId == id && c.Address.Equals(address)).ToArray();
        if (matches.Length != 1) throw new ArgumentException("USB adapter/address is absent or ambiguous; use --list-adapters and select explicitly.");
        var selected = matches[0];
        if (!Usable(peer) || peer.Equals(address) || selected.PrefixLength is < 1 or > 30 || !selected.Contains(peer))
            throw new ArgumentException("Expected peer must be a different usable IPv4 host on the selected adapter subnet.");
        return selected;
    }

    private bool Contains(IPAddress peer)
    {
        var local = Address.GetAddressBytes(); var remote = peer.GetAddressBytes();
        for (var bit = 0; bit < PrefixLength; bit++)
            if ((local[bit / 8] & (1 << (7 - bit % 8))) != (remote[bit / 8] & (1 << (7 - bit % 8)))) return false;
        var hostBits = 32 - PrefixLength;
        var host = System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(remote) & (uint)((1UL << hostBits) - 1);
        return host != 0 && host != (1UL << hostBits) - 1;
    }

    public bool IsPresent()
    {
        try { return Enumerate().Any(c => c == this); }
        catch (NetworkInformationException) { return false; }
    }
    internal static bool AcceptPeer(IPAddress expected, EndPoint remote) => remote is IPEndPoint endpoint && expected.Equals(endpoint.Address);
}
