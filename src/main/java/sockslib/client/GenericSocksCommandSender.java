package sockslib.client;

import sockslib.common.AddressType;
import sockslib.common.ProtocolErrorException;
import sockslib.common.SocksException;
import sockslib.utils.LogMessageBuilder;
import sockslib.utils.LogMessageBuilder.MsgType;
import sockslib.utils.UnsignedByte;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

public class GenericSocksCommandSender implements SocksCommandSender {

	private static final Logger logger = LoggerFactory.getLogger(GenericSocksCommandSender.class);

	private static final int COMMAND_CONNECT = 0x01;

	private static final int LENGTH_OF_IPV4 = 4;

	private static final int LENGTH_OF_IPV6 = 16;

	@Override
	public void sendConnectCommand(Socket socket, InetAddress address, int port, int version)
			throws IOException {
		sendConnectCommand(socket, new InetSocketAddress(address, port), version);
	}

	@Override
	public void sendConnectCommand(Socket socket, SocketAddress socketAddress, int version)
			throws IOException {
		if (!(socketAddress instanceof InetSocketAddress)) {
			throw new IllegalArgumentException("Unsupported address type");
		}

		final InputStream inputStream = socket.getInputStream();
		final OutputStream outputStream = socket.getOutputStream();
		final InetSocketAddress address = (InetSocketAddress) socketAddress;
		final byte[] bytesOfAddress = address.getAddress().getAddress();
		final int ADDRESS_LENGTH = bytesOfAddress.length;
		final int port = address.getPort();
		final byte addressType;
		final byte[] bufferSent;

		if (ADDRESS_LENGTH == LENGTH_OF_IPV4) {
			addressType = ATYPE_IPV4;
			bufferSent = new byte[6 + LENGTH_OF_IPV4];
		} else if (ADDRESS_LENGTH == LENGTH_OF_IPV6) {
			addressType = ATYPE_IPV6;
			bufferSent = new byte[6 + LENGTH_OF_IPV6];
		} else {
			throw new SocksException("Address error");// TODO
		}

		bufferSent[0] = (byte) version;
		bufferSent[1] = (byte) COMMAND_CONNECT;
		bufferSent[2] = RESERVED;
		bufferSent[3] = addressType;
		System.arraycopy(bytesOfAddress, 0, bufferSent, 4, ADDRESS_LENGTH);// copy address bytes
		bufferSent[4 + ADDRESS_LENGTH] = (byte) ((port & 0xff00) >> 8);
		bufferSent[5 + ADDRESS_LENGTH] = (byte) (port & 0xff);

		outputStream.write(bufferSent);
		outputStream.flush();
		logger.debug("{}", LogMessageBuilder.build(bufferSent, MsgType.SEND));

		checkServerReply(inputStream);
	}

	@Override
	public void sendConnectCommand(Socket socket, String host, int port, int version)
			throws IOException {
		final InputStream inputStream = socket.getInputStream();
		final OutputStream outputStream = socket.getOutputStream();
		final int lengthOfHost = host.getBytes().length;
		final byte[] bufferSent = new byte[7 + lengthOfHost];

		bufferSent[0] = (byte) version;
		bufferSent[1] = (byte) COMMAND_CONNECT;
		bufferSent[2] = RESERVED;
		bufferSent[3] = ATYPE_DOMAINNAME;
		bufferSent[4] = (byte) lengthOfHost;
		byte[] bytesOfHost = host.getBytes();
		System.arraycopy(bytesOfHost, 0, bufferSent, 5, lengthOfHost);// copy host bytes.
		bufferSent[5 + host.length()] = (byte) ((port & 0xff00) >> 8);
		bufferSent[6 + host.length()] = (byte) (port & 0xff);

		outputStream.write(bufferSent);
		outputStream.flush();
		logger.debug("{}", LogMessageBuilder.build(bufferSent, MsgType.SEND));

		checkServerReply(inputStream);
	}

	private void checkServerReply(InputStream inputStream)
			throws IOException {
		final byte serverReply;
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		int temp = 0;
		for (int i = 0; i < 4; i++) {
			temp = inputStream.read();
			byteArrayOutputStream.write(temp);
		}

		byte addressType = (byte) temp;
		switch (addressType) {
		case AddressType.IPV4:
			for (int i = 0; i < 6; i++) {
				byteArrayOutputStream.write(inputStream.read());
			}
			break;
		case AddressType.DOMAIN_NAME:
			temp = inputStream.read();
			byteArrayOutputStream.write(temp);
			for (int i = 0; i < temp + 2; i++) {
				byteArrayOutputStream.write(inputStream.read());
			}
			break;
		case AddressType.IPV6:
			for (int i = 0; i < 18; i++) {
				byteArrayOutputStream.write(inputStream.read());
			}
			break;
		default:
			throw new ProtocolErrorException("Address type not support, type value: " + addressType);
		}
		byte[] receivedData = byteArrayOutputStream.toByteArray();
		int length = receivedData.length;
		logger.debug("{}", LogMessageBuilder.build(receivedData, length, MsgType.RECEIVE));
		final byte[] addressBytes;
		byte[] portBytes = new byte[2];

		if (receivedData[3] == AddressType.IPV4) {
			addressBytes = new byte[4];
			System.arraycopy(receivedData, 4, addressBytes, 0, addressBytes.length);
			int a = UnsignedByte.toInt(addressBytes[0]);
			int b = UnsignedByte.toInt(addressBytes[1]);
			int c = UnsignedByte.toInt(addressBytes[2]);
			int d = UnsignedByte.toInt(addressBytes[3]);
			portBytes[0] = receivedData[8];
			portBytes[1] = receivedData[9];

			logger.debug("Server replied:Address as IPv4:{}.{}.{}.{}, port:{}", a, b, c, d,
					(UnsignedByte.toInt(portBytes[0]) << 8) | (UnsignedByte.toInt(portBytes[1])));

		} else if (receivedData[3] == AddressType.DOMAIN_NAME) {
			int size = receivedData[4];
			size = size & 0xFF;
			addressBytes = new byte[size];
			System.arraycopy(receivedData, 4, addressBytes, 0, size);
			portBytes[0] = receivedData[4 + size];
			portBytes[1] = receivedData[5 + size];
			logger.debug("Server replied:Address as host:{}, port:{}", new String(addressBytes),
					(UnsignedByte.toInt(portBytes[0]) << 8) | (UnsignedByte.toInt(portBytes[1])));
		} else if (receivedData[3] == AddressType.IPV6) {
			addressBytes = new byte[16];
			System.arraycopy(receivedData, 4, addressBytes, 0, addressBytes.length);
			logger.debug("Server replied:Address as IPv6:{}", new String(addressBytes));
		}

		serverReply = receivedData[1];

		if (serverReply != REP_SUCCEEDED) {
			throw SocksException.serverReplyException(serverReply);
		}
		logger.debug("SOCKS server response success");
	}

}
