/*
 *  Copyright 2025 Cade Lueker
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
/// an effect for resolving a domain name to an ip address
///
eff Dns {
    def lookup(host: String): IpAddr
    def lookupAll(host: String): List[IpAddr]
}

mod Dns {
    use IoError.ErrorKind
    use IoError.IoError

    import java.io.IOException
    import java.lang.IllegalArgumentException
    import java.net.InetAddress
    import java.net.UnknownHostException

    ///
    /// Handles the `Dns` effect of the given function `f`.
    ///
    /// In other words, re-interprets the `Dns` effect using the `Net` and `IO` effects.
    ///
    pub def handle(f: a -> b \ ef): a -> Result[IoError, b] \ (ef - Dns) + {Net, IO} = x ->
        run {
            Ok(f(x))
        } with handler Dns {
            def lookup(host, k) = {
                try {
                    let address = InetAddress.getByName(host);
                    let addrBytes = Array.toList(address.getAddress());
                    let ipAddr = IpAddr.fromBytes(addrBytes);
                    k(ipAddr)
                } catch {
                    case ex: UnknownHostException     => Err(IoError(ErrorKind.UnknownHost, ex.getMessage()))
                    case ex: IllegalArgumentException => Err(IoError(ErrorKind.InvalidInput, ex.getMessage()))
                    case ex: IOException              => Err(IoError(ErrorKind.Other, ex.getMessage()))
                }
            }

            def lookupAll(host, k) = {
                try {
                    let addrsArr = InetAddress.getAllByName(host);
                    let addresses = addrsArr
                        |> Array.toList
                        |> List.map(a -> a.getAddress() |> Array.toList)
                        |> List.map(IpAddr.fromBytes);
                    k(addresses)
                } catch {
                    case ex: UnknownHostException     => Err(IoError(ErrorKind.UnknownHost, ex.getMessage()))
                    case ex: IllegalArgumentException => Err(IoError(ErrorKind.InvalidInput, ex.getMessage()))
                    case ex: IOException              => Err(IoError(ErrorKind.Other, ex.getMessage()))
                }
            }

        }

    ///
    /// Handles the `Dns` effect of the given function `f`.
    ///
    /// In other words, re-interprets the `Dns` effect using the `Net` and `IO` effects.
    ///
    pub def runWithIO(f: Unit -> a \ ef): Result[IoError, a] \ ef - Dns + {Net, IO} = handle(f)()

}

