import Foundation
import Security
import CryptoKit

public actor Vault {
    private let root: URL
    private let testMode: Bool
    private var memory=[String:Data]()
    public init(directory: URL? = nil, testMode: Bool = false) {
        root=directory ?? FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("Spare",isDirectory:true)
        self.testMode=testMode
    }
    private func keychain(_ key: String) -> [String:Any] {
        [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:"io.github.mot1us.spare.mac",kSecAttrAccount as String:key]
    }
    public func secret(_ key: String) throws -> Data? {
        if testMode { return memory[key] }
        var query=keychain(key); query[kSecReturnData as String]=true; query[kSecMatchLimit as String]=kSecMatchLimitOne
        var value:CFTypeRef?
        let status=SecItemCopyMatching(query as CFDictionary,&value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data=value as? Data else { throw SpareError.storage }
        return data
    }
    public func saveSecret(_ data: Data, key: String) throws {
        if testMode { memory[key]=data; return }
        let query=keychain(key)
        let status=SecItemUpdate(query as CFDictionary,[kSecValueData as String:data] as CFDictionary)
        if status == errSecItemNotFound {
            var item=query; item[kSecValueData as String]=data; item[kSecAttrAccessible as String]=kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(item as CFDictionary,nil) == errSecSuccess else { throw SpareError.storage }
        } else if status != errSecSuccess { throw SpareError.storage }
    }
    private func cacheKey() throws -> SymmetricKey {
        if let data=try secret("cache-key"), data.count == 32 { return SymmetricKey(data:data) }
        let key=SymmetricKey(size:.bits256)
        try saveSecret(key.withUnsafeBytes { Data($0) }, key:"cache-key")
        return key
    }
    public func save<T:Encodable>(_ object:T, name:String) throws {
        let key=try cacheKey(), data=try JSONEncoder().encode(object)
        let encrypted=try AES.GCM.seal(data,using:key).combined!
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        let path=root.appendingPathComponent(TextMatch.stableID(name)+".bin")
        try encrypted.write(to:path,options:.atomic)
        try FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:path.path)
    }
    public func load<T:Decodable>(_ type:T.Type, name:String) throws -> T? {
        let path=root.appendingPathComponent(TextMatch.stableID(name)+".bin")
        guard FileManager.default.fileExists(atPath:path.path) else { return nil }
        guard let size=try path.resourceValues(forKeys:[.fileSizeKey]).fileSize, size <= 128*1024*1024 else { return nil }
        let data=try Data(contentsOf:path)
        let decrypted=try AES.GCM.open(AES.GCM.SealedBox(combined:data),using:cacheKey())
        return try JSONDecoder().decode(type,from:decrypted)
    }
    public func removeCache(name:String) {
        try? FileManager.default.removeItem(at:root.appendingPathComponent(TextMatch.stableID(name)+".bin"))
    }
}
