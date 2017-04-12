/*
 * Copyright (c) 2014, Kustaa Nyholm / SpareTimeLabs
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, 
 * are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list 
 * of conditions and the following disclaimer.
 * 
 * Redistributions in binary form must reproduce the above copyright notice, this 
 * list of conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.
 *  
 * Neither the name of the Kustaa Nyholm or SpareTimeLabs nor the names of its 
 * contributors may be used to endorse or promote products derived from this software 
 * without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, 
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT 
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, 
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */
package purejavahidapi.linux;

import static purejavahidapi.linux.UdevLibrary.BUS_BLUETOOTH;
import static purejavahidapi.linux.UdevLibrary.BUS_USB;
import static purejavahidapi.linux.UdevLibrary.udev_device_get_devnode;
import static purejavahidapi.linux.UdevLibrary.udev_device_get_parent_with_subsystem_devtype;
import static purejavahidapi.linux.UdevLibrary.udev_device_get_sysattr_value;
import static purejavahidapi.linux.UdevLibrary.udev_device_new_from_syspath;
import static purejavahidapi.linux.UdevLibrary.udev_device_unref;
import static purejavahidapi.linux.UdevLibrary.udev_new;
import static purejavahidapi.linux.UdevLibrary.udev_unref;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

import purejavahidapi.linux.UdevLibrary.udev;
import purejavahidapi.linux.UdevLibrary.udev_device;

/* package */class HidDeviceInfo extends purejavahidapi.HidDeviceInfo {

	public HidDeviceInfo(String sysfs_path) throws IOException {
		m_Path = sysfs_path;
		udev_device raw_dev = null;
		udev udev = null;
		udev_device hid_dev=null;
		udev_device usb_dev=null;
		try {
			udev = udev_new();

			raw_dev = udev_device_new_from_syspath(udev, sysfs_path);
			String dev_path = udev_device_get_devnode(raw_dev);
			hid_dev = udev_device_get_parent_with_subsystem_devtype(raw_dev, "hid", null);

			if (hid_dev == null)
				throw new IOException("hid_dev == null");
			
			if (dev_path == null)
				throw new IOException("dev_path == null");
			
			usb_dev = udev_device_get_parent_with_subsystem_devtype(raw_dev, "usb", "usb_device");
			String usb_dev_path = udev_device_get_devnode(usb_dev);
			
			

			Properties p = new Properties();
			p.load(new StringReader(udev_device_get_sysattr_value(hid_dev, "uevent")));
			
			byte[] reportDescriptor = udev_device_get_sysattr_value(hid_dev, "report_descriptor").getBytes(); //DEBUG
			for (int i = 0; i < reportDescriptor.length;) {
			    int key = reportDescriptor[i] & 0xFF;
			    int key_size = 1;
			    int data_size = 1;
			    
			    if ((key & 0xF0) == 0xF0) { //long item
			        if (i + 1 < reportDescriptor.length) {
			            data_size = reportDescriptor[i + 1];
			        } else {
			          //bad report
			          data_size = 0;
			        }
			    } else { //short item
			        data_size = key & 0x03;
                    if (data_size == 3) data_size = 4;
                    
                    if ((key & 0xFC) == 0x04) { //Usage page
                        System.out.println("Found usage page key " + key + " at " + i);
                        //generally 0x01, heuristics from here on out
                        int i2 = i + data_size + 1;
                        if (i2 < reportDescriptor.length) {
                            int key2 = reportDescriptor[i2];
                            if ((key2 & 0xFC) == 0x08) { //Usage
                                int data2_size = key2 & 0x03;
                                if (i2 + data2_size < reportDescriptor.length) {
                                    System.out.println("Found usage! i2: " + i2 + " data2: " + data2_size + " dTest: " + reportDescriptor[i2 + 1]);
                                    if (data2_size == 1) {
                                        m_UsagePage = reportDescriptor[i2 + 1];
                                    } else if (data2_size == 2 || data2_size == 3 /* can't fit int */) {
                                        //TODO assuming big-endian, probably wrong
                                        m_UsagePage = (short)((reportDescriptor[i2 + 1] >> 1) | reportDescriptor[i2 + 2]);
                                    }
                                } else {
                                    System.out.println("i2 + data (" + (i2 + data2_size) + ") too large");
                                }
                            }
                        } else {
                            System.out.println("i2 (" + i2 + ") is too large");
                            //PANIC!
                        }
                    }
			    }
			    
			    i += (key_size + data_size);
			}

			String[] hidId = ((String) p.get("HID_ID")).split(":");
			short bus = (short) Long.parseLong(hidId[0], 16);
			m_DeviceId = usb_dev_path;
			m_VendorId = (short) Long.parseLong(hidId[1], 16);
			m_ProductId = (short) Long.parseLong(hidId[2], 16);

			m_ProductString = (String) p.get("HID_NAME");
			m_SerialNumberString = (String) p.get("HID_UNIQ");

			if (bus != BUS_USB && bus != BUS_BLUETOOTH)
				throw new IOException("bus != BUS_USB && bus != BUS_BLUETOOTH ");

			switch (bus) {
				case BUS_USB:
					/*
					 * The device pointed to by raw_dev contains information
					 * about the hidraw device. In order to get information
					 * about the USB device, get the parent device with the
					 * subsystem/devtype pair of "usb"/"usb_device". This will
					 * be several levels up the tree, but the function will find
					 * it.
					 */
					if (usb_dev == null)
						throw new IOException("usb_dev == null");

					/* Get a handle to the interface's udev node. */
					udev_device intf_dev = udev_device_get_parent_with_subsystem_devtype(raw_dev, "usb", "usb_interface");
					if (intf_dev != null) {
						String str = udev_device_get_sysattr_value(intf_dev, "bInterfaceNumber");
						// cur_dev->interface_number = (str)?
						// strtol(str, NULL, 16): -1;
					}

					break;

				case BUS_BLUETOOTH:
					/* Manufacturer and Product strings */
					// cur_dev->manufacturer_string = wcsdup(L"");
					// cur_dev->product_string =
					// utf8_to_wchar_t(product_name_utf8);

					break;

				default:
					// can't happen
					break;
			}
		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			if (raw_dev != null)
				udev_device_unref(raw_dev);
			if (udev != null)
				udev_unref(udev);
			

		}
	}
}
