/*
 * Automatically generated by jrpcgen 1.0.7 on 2/21/09 1:22 AM
 * jrpcgen is part of the "Remote Tea" ONC/RPC package for Java
 * See http://remotetea.sourceforge.net for details
 */
package org.dcache.chimera.nfs.v4.xdr;
import org.dcache.chimera.nfs.v4.*;
import org.dcache.xdr.*;
import java.io.IOException;

public class COMPOUND4args implements XdrAble {
    public utf8str_cs tag;
    public uint32_t minorversion;
    public nfs_argop4 [] argarray;

    public COMPOUND4args() {
    }

    public COMPOUND4args(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        xdrDecode(xdr);
    }

    public void xdrEncode(XdrEncodingStream xdr)
           throws OncRpcException, IOException {
        tag.xdrEncode(xdr);
        minorversion.xdrEncode(xdr);
        { int $size = argarray.length; xdr.xdrEncodeInt($size); for ( int $idx = 0; $idx < $size; ++$idx ) { argarray[$idx].xdrEncode(xdr); } }
    }

    public void xdrDecode(XdrDecodingStream xdr)
           throws OncRpcException, IOException {
        tag = new utf8str_cs(xdr);
        minorversion = new uint32_t(xdr);
        { int $size = xdr.xdrDecodeInt(); argarray = new nfs_argop4[$size]; for ( int $idx = 0; $idx < $size; ++$idx ) { argarray[$idx] = new nfs_argop4(xdr); } }
    }

}
// End of COMPOUND4args.java