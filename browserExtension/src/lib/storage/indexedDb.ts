// A key-value area in the IndexedDB of the extension's origin, for secrets.
// Only the extension's own pages and the background can open it: content
// scripts run in the page's origin, while browsers expose `storage.local` to
// them, and so to a compromised renderer.
import type { StorageArea } from "../pairing/store";

const DATABASE = "keyguard";
const VERSION = 1;
const STORE = "values";

export class IndexedDbArea implements StorageArea {
  private database: Promise<IDBDatabase> | null = null;

  constructor(
    private readonly factory: IDBFactory,
    private readonly name: string = DATABASE,
  ) {}

  async get(key: string): Promise<Record<string, unknown>> {
    const value = await this.run<unknown>("readonly", (store) => store.get(key));
    return value === undefined ? {} : { [key]: value };
  }

  async set(items: Record<string, unknown>): Promise<void> {
    await this.run("readwrite", (store) => {
      for (const [key, value] of Object.entries(items)) store.put(value, key);
    });
  }

  async remove(key: string): Promise<void> {
    await this.run("readwrite", (store) => store.delete(key));
  }

  private open(): Promise<IDBDatabase> {
    this.database ??= new Promise<IDBDatabase>((resolve, reject) => {
      const request = this.factory.open(this.name, VERSION);
      request.onupgradeneeded = () => request.result.createObjectStore(STORE);
      request.onsuccess = () => {
        const database = request.result;
        // Another version wants the database, or the browser closed it: open again next time.
        database.onversionchange = () => {
          database.close();
          this.database = null;
        };
        database.onclose = () => (this.database = null);
        resolve(database);
      };
      request.onerror = () => reject(request.error ?? new Error("IndexedDB failed to open"));
    }).catch((error: unknown) => {
      this.database = null;
      throw error;
    });
    return this.database;
  }

  private async run<T>(
    mode: IDBTransactionMode,
    action: (store: IDBObjectStore) => IDBRequest | void,
  ): Promise<T | undefined> {
    const database = await this.open();
    return new Promise<T | undefined>((resolve, reject) => {
      let transaction: IDBTransaction;
      try {
        // Strict: a key that the app pinned must survive a crash.
        transaction = database.transaction(STORE, mode, { durability: "strict" });
      } catch (error) {
        this.database = null;
        reject(error);
        return;
      }
      const request = action(transaction.objectStore(STORE));
      transaction.oncomplete = () => resolve(request ? (request.result as T) : undefined);
      transaction.onerror = () => reject(transaction.error ?? new Error("IndexedDB transaction failed"));
      transaction.onabort = () => reject(transaction.error ?? new Error("IndexedDB transaction aborted"));
    });
  }
}
