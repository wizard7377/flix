/*
 *  Copyright 2025 Ry Wiese
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

///
/// The effect used to connect to a TCP server.
///
eff TcpConnectWithResult {
    def connect(ip: IpAddr, port: Int16): Result[IoError, TcpSocket]
}

mod TcpConnectWithResult {
    use IoError.ErrorKind
    use IoError.IoError

    import java.net.InetAddress
    import java.net.Socket
    import java.io.IOException

    ///
    /// Handles the `TcpConnectWithResult` effect of the given function `f`.
    ///
    /// In other words, re-interprets the `TcpConnectWithResult` effect using the `Net` and `IO` effects.
    ///
    pub def handle(f: a -> b \ ef): a -> b \ ef - TcpConnectWithResult + {IO, Net} = x ->
        run {
            f(x)
        } with handler TcpConnectWithResult {
            def connect(ip, port, k) = {
                try {
                    let inet: InetAddress = InetAddress.getByAddress(IpAddr.toBytes(ip));
                    let socket = new Socket(inet, Int16.toInt32(port));
                    let inputStream = socket.getInputStream();
                    let outputStream = socket.getOutputStream();
                    k(Ok(TcpSocket.TcpSocket(socket, inputStream, outputStream)))
                } catch {
                    case ex: IOException => k(Err(IoError(ErrorKind.Other, ex.getMessage())))
                }
            }
        }

    ///
    /// Runs the `TcpConnectWithResult` effect of the given function `f`.
    ///
    /// In other words, re-interprets the `TcpConnectWithResult` effect using the `Net` and `IO` effects.
    ///
    pub def runWithIO(f: Unit -> a \ ef): a \ ef - TcpConnectWithResult + {IO, Net} = handle(f)()

}
