using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace Valcombe.Launcher.Services;

/// <summary>État du serveur Minecraft (protocole de « ping » de la liste des serveurs).</summary>
public static class ServerStatus
{
    public sealed record Result(bool Online, int Players, int Max, IReadOnlyList<string> Names);

    private static void WriteVarInt(Stream s, int value)
    {
        uint v = (uint)value;
        do
        {
            byte b = (byte)(v & 0x7F);
            v >>= 7;
            if (v != 0)
            {
                b |= 0x80;
            }
            s.WriteByte(b);
        } while (v != 0);
    }

    private static int ReadVarInt(Stream s)
    {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7)
        {
            int b = s.ReadByte();
            if (b < 0)
            {
                throw new EndOfStreamException();
            }
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
            {
                return result;
            }
        }
        throw new InvalidDataException("VarInt trop long.");
    }

    private static byte[] Packet(int id, Action<MemoryStream> body)
    {
        using var data = new MemoryStream();
        WriteVarInt(data, id);
        body(data);
        using var framed = new MemoryStream();
        WriteVarInt(framed, (int)data.Length);
        data.WriteTo(framed);
        return framed.ToArray();
    }

    public static async Task<Result> PingAsync(string address, CancellationToken ct = default)
    {
        string host = address.Trim();
        int port = 25565;
        int colon = host.LastIndexOf(':');
        if (colon > 0 && int.TryParse(host[(colon + 1)..], out int p))
        {
            port = p;
            host = host[..colon];
        }
        try
        {
            using var client = new TcpClient();
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(4));
            await client.ConnectAsync(host, port, timeout.Token);
            using var stream = client.GetStream();
            stream.ReadTimeout = 4000;
            byte[] handshake = Packet(0x00, s =>
            {
                WriteVarInt(s, -1);
                byte[] h = Encoding.UTF8.GetBytes(host);
                WriteVarInt(s, h.Length);
                s.Write(h);
                s.WriteByte((byte)(port >> 8));
                s.WriteByte((byte)port);
                WriteVarInt(s, 1);
            });
            await stream.WriteAsync(handshake, timeout.Token);
            await stream.WriteAsync(Packet(0x00, _ => { }), timeout.Token);
            ReadVarInt(stream); // longueur
            ReadVarInt(stream); // identifiant
            int len = ReadVarInt(stream);
            byte[] json = new byte[len];
            int read = 0;
            while (read < len)
            {
                int n = await stream.ReadAsync(json.AsMemory(read, len - read), timeout.Token);
                if (n == 0)
                {
                    throw new EndOfStreamException();
                }
                read += n;
            }
            using var doc = JsonDocument.Parse(json);
            var players = doc.RootElement.GetProperty("players");
            var names = new List<string>();
            if (players.TryGetProperty("sample", out var sample))
            {
                foreach (var e in sample.EnumerateArray())
                {
                    names.Add(e.GetProperty("name").GetString() ?? "");
                }
            }
            return new Result(true, players.GetProperty("online").GetInt32(), players.GetProperty("max").GetInt32(), names);
        }
        catch (Exception)
        {
            return new Result(false, 0, 0, Array.Empty<string>());
        }
    }
}
