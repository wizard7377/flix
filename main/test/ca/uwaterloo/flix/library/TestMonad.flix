/*
 *  Copyright 2021 Stephen Tetley
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

mod TestMonad {

    use Monad.{flatten, kleisliLeft, kleisliRight}
    use Monad.{=<<, >>=, >=>, <=<}

    /////////////////////////////////////////////////////////////////////////////
    // flatten                                                                 //
    /////////////////////////////////////////////////////////////////////////////

    @Test
    def flatten01(): Bool =
        let x: Option[Option[Int32]] = None;
        flatten(x) == None

    @Test
    def flatten02(): Bool =
        let x: Option[Option[Int32]] = Some(None);
        flatten(x) == None

    @Test
    def flatten03(): Bool =
        let x: Option[Option[Int32]] = Some(Some(1));
        flatten(x) == Some(1)

    /////////////////////////////////////////////////////////////////////////////
    // kleisliLeft                                                             //
    /////////////////////////////////////////////////////////////////////////////

    @Test
    def kleisliLeft01(): Bool =
        kleisliLeft(_ -> (None: Option[Int32]), x2 -> Some(ToString.toString(x2)), 1) == None

    @Test
    def kleisliLeft02(): Bool =
        kleisliLeft(x1 -> Some(x1 + 1), x2 -> Some(ToString.toString(x2)), 1) == Some("2")

    /////////////////////////////////////////////////////////////////////////////
    // kleisliRight                                                            //
    /////////////////////////////////////////////////////////////////////////////

    @Test
    def kleisliRight01(): Bool =
        kleisliRight(x1 -> Some(ToString.toString(x1)), _ -> (None: Option[Int32]), 1) == None

    @Test
    def kleisliRight02(): Bool =
        kleisliRight(x1 -> Some(ToString.toString(x1)), x2 -> Some(x2 + 1), 1) == Some("2")

    /////////////////////////////////////////////////////////////////////////////
    // symFlatMap (=<<)                                                        //
    /////////////////////////////////////////////////////////////////////////////

    @Test
    def symFlatMap01(): Bool =
        (x -> Some(x + 1)) =<< None == None

    @Test
    def symFlatMap02(): Bool =
        (x -> Some(x + 1)) =<< Some(1) == Some(2)

    /////////////////////////////////////////////////////////////////////////////
    // symBind (>>=)                                                           //
    /////////////////////////////////////////////////////////////////////////////

    @Test
    def symBind01(): Bool =
        None >>= (x -> Some(x + 1)) == None

    @Test
    def symBind02(): Bool =
        Some(1) >>= (x -> Some(x + 1)) == Some(2)

    /////////////////////////////////////////////////////////////////////////////
    // symKleisliLeft (>=>)                                                    //
    /////////////////////////////////////////////////////////////////////////////

    @Test
    def symKleisliLeft01(): Bool =
        let mf = (_ -> (None: Option[Int32])) >=> (x2 -> Some(ToString.toString(x2)));
        mf(1) == None

    @Test
    def symKleisliLeft02(): Bool =
        let mf = (x1 -> Some(x1 + 1)) >=> (x2 -> Some(ToString.toString(x2)));
        mf(1) == Some("2")

    /////////////////////////////////////////////////////////////////////////////
    // symKleisliRight (<=<)                                                   //
    /////////////////////////////////////////////////////////////////////////////

    @Test
    def symKleisliRight01(): Bool =
        let mf = (x1 -> Some(ToString.toString(x1))) <=< (_ -> (None: Option[Int32]));
        mf(1) == None

    @Test
    def symKleisliRight02(): Bool =
        let mf = (x1 -> Some(ToString.toString(x1))) <=< (x2 -> Some(x2 + 1));
        mf(1) == Some("2")

}

