/*
 * Copyright 2023 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

///
/// An error that can occur when performing I/O operations involving the file system.
///
/// The first element describes the general kind of error that occurred.
/// This can be used to determine the correct action to take to handle specific kinds of errors.
///
/// The second element is a string that describes the error, meant to be used for debugging purposes.
///
pub enum IoError(IoError.ErrorKind, String) with Eq, ToString

mod IoError {

    pub enum ErrorKind with Eq, ToString {
        case AlreadyExists,
        case ConnectionFailed,
        case Interrupted,
        case InvalidPath,
        case InvalidInput,
        case InvalidData,
        case NotFound,
        case NotFile,
        case NotDirectory,
        case PermissionDenied,
        case Timeout,
        case UnexpectedEof,
        case Unsupported,
        case UnknownHost,
        case Other
    }

}
