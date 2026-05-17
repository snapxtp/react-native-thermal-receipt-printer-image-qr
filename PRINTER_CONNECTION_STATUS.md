# Printer Connection Status & Special Character Encoding

Android'de Network ve USB yazıcılar için bağlantı durumu kontrolü ve özel karakter (Türkçe, Azerice, Kiril, Arapça vb.) desteği.

---

## Network Printer

### `NetPrinter.isConnected()`

O anki socket durumunu sorgular. Asenkron, `Promise<boolean>` döner.

```ts
import { NetPrinter } from 'react-native-thermal-receipt-printer-image-qr';

const connected = await NetPrinter.isConnected();
console.log(connected); // true | false
```

> **Not:** Bu metod Java socket nesnesinin _bilinen_ durumunu döner. Ağ kablosu çekilmesi gibi durumlarda OS henüz bağlantı kopuşunu algılamamış olabilir. Gerçek zamanlı kopuş tespiti için `startMonitoring()` kullanın.

---

### `NetPrinter.startMonitoring(intervalMs?)`

Arka planda periyodik bağlantı kontrolü başlatır. Bağlantı kopunca `EVENT_NET_PRINTER_DISCONNECTED` eventi yayar, socket'i kapatır ve monitoring'i durdurur.

| Parametre | Tip | Varsayılan | Açıklama |
|-----------|-----|------------|----------|
| `intervalMs` | `number` | `5000` | Kontrol aralığı (ms) |

```ts
import {
  NetPrinter,
  NetPrinterEventEmitter,
  RN_THERMAL_RECEIPT_PRINTER_EVENTS,
} from 'react-native-thermal-receipt-printer-image-qr';

// Yazıcıya bağlandıktan sonra monitoring'i başlat
await NetPrinter.connectPrinter('192.168.1.100', 9100);
NetPrinter.startMonitoring(4000); // 4 saniyede bir kontrol

// Bağlantı kopunca tetiklenir
const subscription = NetPrinterEventEmitter.addListener(
  RN_THERMAL_RECEIPT_PRINTER_EVENTS.EVENT_NET_PRINTER_DISCONNECTED,
  (data: { host: string; port: number }) => {
    console.warn(`Yazıcı bağlantısı kesildi: ${data.host}:${data.port}`);
    // Alert göster, yeniden bağlanmayı dene, vb.
  }
);

// Ekran unmount olurken temizle
return () => {
  NetPrinter.stopMonitoring();
  subscription.remove();
};
```

---

### `NetPrinter.stopMonitoring()`

Periyodik kontrol thread'ini durdurur. `closeConn()` çağrıldığında monitoring otomatik durur, ancak elle de durdurabilirsiniz.

```ts
NetPrinter.stopMonitoring();
```

---

### Tam Kullanım Örneği (React)

```tsx
import React, { useEffect, useRef } from 'react';
import { Alert } from 'react-native';
import {
  NetPrinter,
  NetPrinterEventEmitter,
  RN_THERMAL_RECEIPT_PRINTER_EVENTS,
} from 'react-native-thermal-receipt-printer-image-qr';

export function usePrinterConnection(host: string, port: number) {
  const subscriptionRef = useRef<any>(null);

  useEffect(() => {
    let active = true;

    async function connect() {
      await NetPrinter.init();
      await NetPrinter.connectPrinter(host, port);

      // 5 saniyede bir bağlantı kontrolü
      NetPrinter.startMonitoring(5000);

      subscriptionRef.current = NetPrinterEventEmitter.addListener(
        RN_THERMAL_RECEIPT_PRINTER_EVENTS.EVENT_NET_PRINTER_DISCONNECTED,
        (data) => {
          if (!active) return;
          Alert.alert('Yazıcı Bağlantısı Kesildi', `${data.host}:${data.port}`);
        }
      );
    }

    connect().catch(console.error);

    return () => {
      active = false;
      NetPrinter.stopMonitoring();
      NetPrinter.closeConn();
      subscriptionRef.current?.remove();
    };
  }, [host, port]);

  return { isConnected: NetPrinter.isConnected };
}
```

---

## USB Printer

### `USBPrinter.isConnected()`

USB cihaz bağlantı durumunu sorgular. Şunları kontrol eder:
- Cihaz seçilmiş mi (`connectPrinter` çağrıldı mı)
- USB izni mevcut mu
- Aktif bir USB connection nesnesi var mı

```ts
import { USBPrinter } from 'react-native-thermal-receipt-printer-image-qr';

const connected = await USBPrinter.isConnected();
console.log(connected); // true | false
```

---

### USB Fiziksel Kopuş Tespiti (Event)

USB kablosu fiziksel olarak çekildiğinde sistem `usbDetached` eventini otomatik yayar. Bu altyapı zaten mevcuttur, sadece dinlemeniz yeterlidir.

```tsx
import { useEffect } from 'react';
import {
  USBPrinterEventEmitter,
  RN_THERMAL_RECEIPT_PRINTER_EVENTS,
} from 'react-native-thermal-receipt-printer-image-qr';

useEffect(() => {
  const detachSub = USBPrinterEventEmitter.addListener(
    RN_THERMAL_RECEIPT_PRINTER_EVENTS.EVENT_USB_DEVICE_DETACHED,
    (device) => {
      console.warn('USB yazıcı çıkarıldı:', device?.device_name);
      // Uyarı göster veya bağlantı durumunu güncelle
    }
  );

  const attachSub = USBPrinterEventEmitter.addListener(
    RN_THERMAL_RECEIPT_PRINTER_EVENTS.EVENT_USB_DEVICE_ATTACHED,
    (device) => {
      console.log('USB yazıcı takıldı:', device?.device_name);
    }
  );

  return () => {
    detachSub.remove();
    attachSub.remove();
  };
}, []);
```

---

### Periyodik `isConnected` Kontrolü (USB)

USB için monitoring event'i yoktur; gerekirse manuel polling yapabilirsiniz:

```ts
useEffect(() => {
  const interval = setInterval(async () => {
    const connected = await USBPrinter.isConnected();
    if (!connected) {
      console.warn('USB yazıcı bağlı değil');
    }
  }, 5000);

  return () => clearInterval(interval);
}, []);
```

---

## Platform Desteği

| Özellik | Android | iOS |
|---------|---------|-----|
| `NetPrinter.isConnected()` | ✅ | ❌ |
| `NetPrinter.startMonitoring()` | ✅ | ❌ |
| `NetPrinter.stopMonitoring()` | ✅ | ❌ |
| `EVENT_NET_PRINTER_DISCONNECTED` | ✅ | ❌ |
| `USBPrinter.isConnected()` | ✅ | ❌ |
| `EVENT_USB_DEVICE_DETACHED` | ✅ | ❌ |
| `EVENT_USB_DEVICE_ATTACHED` | ✅ | ❌ |
| `codepage` (özel karakter desteği) | ✅ | ❌ |

---

## Özel Karakter Desteği (Codepage)

### Sorun

Termal fiş yazıcıları varsayılan olarak UTF-8 **anlamazlar**. `ə`, `ğ`, `ş` gibi ASCII dışı karakterler gönderildiğinde yazıcı bunları tek-byte codepage'iyle yorumlar ve anlamsız semboller basar. Çözüm: yazıcıya önce hangi karakter tablosunu kullanacağını söyleyen `ESC t n` ESC/POS komutunu göndermek, ardından metni o tabloya göre encode etmek.

### Yeni `codepage` Seçeneği

`PrinterOptions`'a `codepage?: number` eklendi. Belirtildiğinde metin verisinin önüne otomatik olarak `ESC t [codepage]` baytı eklenir.

```ts
// Türkçe karakterler için
NetPrinter.printText("Müşteri: Şahin\nTarih: 17.05.2026", {
  encoding: "CP857",
  codepage: 16,
});
```

Bu şekilde yazıcı önce codepage 16 (CP857/Türkçe DOS) moduna geçer, ardından metnin CP857 baytlarını doğru karakterlere dönüştürür.

---

### Kodlama Eşleştirme Tablosu

`codepage` (ESC/POS `ESC t n` değeri) ile `encoding` (iconv-lite charset) **birbirine uyumlu** seçilmelidir.

| Dil | `codepage` | `encoding` | Notlar |
|-----|-----------|------------|--------|
| Varsayılan (ASCII) | — | `"UTF8"` | Codepage belirtme; sadece ASCII güvenli |
| Türkçe | `16` | `"CP857"` | ğ ı ö ü ş ç — Epson/XPrinter standart |
| Türkçe (Windows) | `35` | `"CP1254"` | Windows Turkish, bazı yazıcılarda gerekir |
| Çok dilli Latin | `2` | `"CP850"` | Batı Avrupa dilleri |
| Kiril | `33` | `"CP1251"` | Rusça, Bulgarca, vb. |
| Arapça | `37` | `"CP1256"` | Windows Arabic |
| Yunanca | `17` | `"CP737"` | DOS Greek |
| Macarca/Çekçe | `32` | `"CP1250"` | Windows Central European |

> **Not:** `codepage` değerleri yazıcı modeline göre değişebilir. Yukarıdaki değerler Epson TM serisi ve birçok Çin menşeli POS yazıcısı için geçerlidir. Kendi yazıcınızın teknik kılavuzuna bakın.

---

### Azerbaycan Türkçesi — `ə` (Schwa) Özel Durumu

`ə` harfi **hiçbir standart 8-bit codepage'de bulunmaz** (CP857, CP1254, CP1252 dahil). Bu nedenle:

**Seçenek 1 — UTF-8 destekleyen yazıcı (önerilen):**
Sunmi, bazı Xprinter ve Honeywell modelleri UTF-8 baytlarını doğrudan kabul eder. Bu durumda `codepage` belirtmeyin; varsayılan UTF-8 encoding çalışır.

```ts
// UTF-8 destekleyen yazıcıda: codepage YOK, encoding varsayılan
NetPrinter.printText("Müştəri: Əli\nCəmi: 12.50 ₼");
```

**Seçenek 2 — Görüntü olarak yazdır:**
`ə` içeren metin bir görsele dönüştürülüp `printImage` veya `printImageBase64` ile gönderilir. Her yazıcıda çalışır.

**Seçenek 3 — Transliterasyon:**
`ə → e`, `ğ → g` gibi ASCII'ye yaklaştırma — kalite kaybı var ama evrensel çalışır.

---

### Tam Kullanım Örneği

```tsx
import { NetPrinter, USBPrinter } from 'react-native-thermal-receipt-printer-image-qr';

// Türkçe metin — CP857 codepage
async function printTurkishReceipt() {
  await NetPrinter.connectPrinter('192.168.1.100', 9100);

  NetPrinter.printBill(
    "<C>FİŞ</C>\n" +
    "Ürün: Şeker\n" +
    "Miktar: 2 kg\n" +
    "Müşteri: Öztürk\n",
    { encoding: "CP857", codepage: 16, cut: true }
  );
}

// USB yazıcı — aynı seçenek
USBPrinter.printText("Sağlıklı günler!\n", { encoding: "CP857", codepage: 16 });
```

---

## Değiştirilen Dosyalar

| Dosya | Değişiklik |
|-------|------------|
| `android/.../adapter/NetPrinterAdapter.java` | `isConnected()`, `startMonitoring()`, `stopMonitoring()`, `SO_KEEPALIVE` |
| `android/.../RNNetPrinterModule.java` | `@ReactMethod isConnected`, `startNetPrinterMonitoring`, `stopNetPrinterMonitoring` |
| `android/.../adapter/USBPrinterAdapter.java` | `isConnected()` |
| `android/.../RNUSBPrinterModule.java` | `@ReactMethod isConnected` |
| `src/utils/EPToolkit.ts` | `IOptions.codepage`, `ESC t n` komutu inject |
| `src/index.ts` | `PrinterOptions.codepage`, bağlantı API'leri |
