using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace SlideShow;

public sealed class HttpsCertificateStore
{
    private const string RootName = "Slide Show Local Root";
    private readonly string _rootPath;

    public HttpsCertificateStore()
    {
        var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        var directory = Path.Combine(appData, "Slide Show", "certificates");
        Directory.CreateDirectory(directory);
        _rootPath = Path.Combine(directory, "slide-show-local-root.pfx");

        RootCertificate = GetOrCreateRootCertificate();
        ServerCertificate = CreateServerCertificate(RootCertificate);
        RootCertificateBytes = RootCertificate.Export(X509ContentType.Cert);
    }

    public X509Certificate2 RootCertificate { get; }

    public X509Certificate2 ServerCertificate { get; }

    public byte[] RootCertificateBytes { get; }

    private X509Certificate2 GetOrCreateRootCertificate()
    {
        if (File.Exists(_rootPath))
        {
            try
            {
                return new X509Certificate2(_rootPath, string.Empty, X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);
            }
            catch
            {
            }
        }

        using var rsa = RSA.Create(4096);
        var request = new CertificateRequest($"CN={RootName}", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.KeyCertSign | X509KeyUsageFlags.CrlSign | X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));

        using var created = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        var persisted = new X509Certificate2(created.Export(X509ContentType.Pfx, string.Empty), string.Empty, X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);
        File.WriteAllBytes(_rootPath, persisted.Export(X509ContentType.Pfx, string.Empty));
        return persisted;
    }

    private static X509Certificate2 CreateServerCertificate(X509Certificate2 root)
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest("CN=Slide Show LAN", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName("localhost");
        san.AddDnsName(Environment.MachineName);
        san.AddIpAddress(IPAddress.Loopback);
        san.AddIpAddress(IPAddress.IPv6Loopback);
        foreach (var address in GetLanAddresses())
        {
            san.AddIpAddress(address);
        }

        request.CertificateExtensions.Add(san.Build());
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection
        {
            new("1.3.6.1.5.5.7.3.1")
        }, false));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));

        var serial = RandomNumberGenerator.GetBytes(16);
        using var signed = request.Create(root, DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(180), serial);
        using var withPrivateKey = signed.CopyWithPrivateKey(rsa);
        return new X509Certificate2(withPrivateKey.Export(X509ContentType.Pfx, string.Empty), string.Empty, X509KeyStorageFlags.Exportable | X509KeyStorageFlags.PersistKeySet);
    }

    private static IEnumerable<IPAddress> GetLanAddresses()
    {
        return NetworkInterface.GetAllNetworkInterfaces()
            .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up)
            .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses)
            .Where(address => address.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(address => address.Address)
            .Where(address => !IPAddress.IsLoopback(address))
            .Where(address => !address.ToString().StartsWith("169.254.", StringComparison.Ordinal))
            .Distinct();
    }
}
