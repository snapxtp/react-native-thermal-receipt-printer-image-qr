# Printer Connection Status

Android'de Network ve USB yazıcılar için bağlantı durumu kontrolü.

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

---

## Değiştirilen Dosyalar

| Dosya | Değişiklik |
|-------|------------|
| `android/.../adapter/NetPrinterAdapter.java` | `isConnected()`, `startMonitoring()`, `stopMonitoring()`, `SO_KEEPALIVE` |
| `android/.../RNNetPrinterModule.java` | `@ReactMethod isConnected`, `startNetPrinterMonitoring`, `stopNetPrinterMonitoring` |
| `android/.../adapter/USBPrinterAdapter.java` | `isConnected()` |
| `android/.../RNUSBPrinterModule.java` | `@ReactMethod isConnected` |
| `src/index.ts` | `NetPrinter.isConnected/startMonitoring/stopMonitoring`, `USBPrinter.isConnected`, `EVENT_NET_PRINTER_DISCONNECTED` |
