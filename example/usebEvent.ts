import {
  USBPrinter,
  USBPrinterEventEmitter,
  RN_THERMAL_RECEIPT_PRINTER_EVENTS,
} from "react-native-thermal-receipt-printer-image-qr";

let attachedSub: { remove: () => void } | null = null;
let detachedSub: { remove: () => void } | null = null;

export async function setupUsbPrinter(vendorId: number, productId: number) {
  await USBPrinter.init();
  await USBPrinter.connectPrinter(vendorId, productId);

  detachedSub = USBPrinterEventEmitter.addListener(
    RN_THERMAL_RECEIPT_PRINTER_EVENTS.EVENT_USB_DEVICE_DETACHED,
    () => {
      console.log("USB printer detached");
      // burada UI state: connected = false
    }
  );

  attachedSub = USBPrinterEventEmitter.addListener(
    RN_THERMAL_RECEIPT_PRINTER_EVENTS.EVENT_USB_DEVICE_ATTACHED,
    async () => {
      console.log("USB printer attached, reconnecting...");
      try {
        await USBPrinter.connectPrinter(vendorId, productId);
        // burada UI state: connected = true
      } catch (e) {
        console.warn("Reconnect failed", e);
      }
    }
  );
}

export function cleanupUsbPrinterListeners() {
  attachedSub?.remove();
  detachedSub?.remove();
  attachedSub = null;
  detachedSub = null;
}