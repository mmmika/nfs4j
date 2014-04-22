/*
 * Copyright (c) 2009 - 2014 Deutsches Elektronen-Synchroton,
 * Member of the Helmholtz Association, (DESY), HAMBURG, GERMANY
 *
 * This library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Library General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program (see the file COPYING.LIB for more
 * details); if not, write to the Free Software Foundation, Inc.,
 * 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.dcache.nfs.v4;

import com.google.common.base.Optional;
import java.io.IOException;
import org.dcache.nfs.v4.xdr.open_delegation_type4;
import org.dcache.nfs.v4.xdr.change_info4;
import org.dcache.nfs.v4.xdr.bitmap4;
import org.dcache.nfs.v4.xdr.nfs4_prot;
import org.dcache.nfs.v4.xdr.nfs_argop4;
import org.dcache.nfs.v4.xdr.changeid4;
import org.dcache.nfs.nfsstat;
import org.dcache.nfs.v4.xdr.uint32_t;
import org.dcache.nfs.v4.xdr.opentype4;
import org.dcache.nfs.v4.xdr.open_claim_type4;
import org.dcache.nfs.v4.xdr.open_delegation4;
import org.dcache.nfs.v4.xdr.createmode4;
import org.dcache.nfs.v4.xdr.nfs_opnum4;
import org.dcache.nfs.v4.xdr.OPEN4resok;
import org.dcache.nfs.v4.xdr.OPEN4res;
import org.dcache.nfs.ChimeraNFSException;
import org.dcache.chimera.FileExistsChimeraFsException;
import org.dcache.chimera.FileNotFoundHimeraFsException;
import org.dcache.nfs.status.AccessException;
import org.dcache.nfs.status.BadXdrException;
import org.dcache.nfs.status.ExistException;
import org.dcache.nfs.status.GraceException;
import org.dcache.nfs.status.InvalException;
import org.dcache.nfs.status.IsDirException;
import org.dcache.nfs.status.NotDirException;
import org.dcache.nfs.status.NoGraceException;
import org.dcache.nfs.status.StaleClientidException;
import org.dcache.nfs.status.SymlinkException;
import org.dcache.nfs.status.WrongTypeException;
import org.dcache.nfs.v4.xdr.fattr4_size;
import org.dcache.nfs.v4.xdr.mode4;
import org.dcache.nfs.v4.xdr.nfs_resop4;
import org.dcache.nfs.vfs.Inode;
import org.dcache.nfs.vfs.Stat;
import org.dcache.xdr.OncRpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperationOPEN extends AbstractNFSv4Operation {

    private static final Logger _log = LoggerFactory.getLogger(OperationOPEN.class);

    OperationOPEN(nfs_argop4 args) {
        super(args, nfs_opnum4.OP_OPEN);
    }

    @Override
    public void process(CompoundContext context, nfs_resop4 result) throws ChimeraNFSException, IOException, OncRpcException {
        final OPEN4res res = result.opopen;

        try {

            NFS4Client client;
            if (context.getMinorversion() > 0) {
                client = context.getSession().getClient();
            } else {
                Long clientid = _args.opopen.owner.value.clientid.value;
                client = context.getStateHandler().getClientByID(clientid);

                if (client == null || !client.isConfirmed()) {
                    throw new StaleClientidException("bad client id.");
                }

                client.updateLeaseTime();
                _log.debug("open request form clientid: {}, owner: {}",
                        client, new String(_args.opopen.owner.value.owner));
            }

            res.resok4 = new OPEN4resok();
            res.resok4.attrset = new bitmap4();
            res.resok4.attrset.value = new int[] {0, 0};
            res.resok4.delegation = new open_delegation4();
            res.resok4.delegation.delegation_type = open_delegation_type4.OPEN_DELEGATE_NONE;
            res.resok4.cinfo = new change_info4();
            res.resok4.cinfo.atomic = true;

            switch (_args.opopen.claim.claim) {

                case open_claim_type4.CLAIM_NULL:

		    if (client.needReclaim() && !context.getStateHandler().hasGracePeriodExpired()) {
			throw new GraceException();
		    }

                    Stat stat = context.getFs().getattr(context.currentInode());
                    if (stat.type() != Stat.Type.DIRECTORY) {
                        throw new NotDirException();
                    }
                    res.resok4.cinfo.before = new changeid4(stat.getCTime());
                    String name = NameFilter.convert(_args.opopen.claim.file.value);
                    _log.debug("regular open for : {}", name);

                    Inode inode;
                    if (_args.opopen.openhow.opentype == opentype4.OPEN4_CREATE) {

                        boolean exclusive = (_args.opopen.openhow.how.mode == createmode4.EXCLUSIVE4) ||
                                (_args.opopen.openhow.how.mode == createmode4.EXCLUSIVE4_1);

                        try {

			    /**
			     * according tho the spec. client MAY send all allowed attributes.
			     * Nevertheless, in reality, clients send only mode.
			     * We will accept only mode and client will send extra
			     * SETATTR is required.
			     *
			     * REVISIT: we can apply all others as well to avoid
			     * extra network roundtrip.
			     */
			    int mode = 0600;
			    AttributeMap attributeMap;
			    bitmap4 appliedAttribytes = bitmap4.of(0);
			    switch(_args.opopen.openhow.how.mode) {
				case createmode4.UNCHECKED4:
				case createmode4.GUARDED4:
				    attributeMap = new AttributeMap(_args.opopen.openhow.how.createattrs);
				    break;
				case createmode4.EXCLUSIVE4:
				    attributeMap = new AttributeMap(null);
				    break;
				case createmode4.EXCLUSIVE4_1:
				    attributeMap = new AttributeMap(_args.opopen.openhow.how.ch_createboth.cva_attrs);
				    break;
				default:
				    throw new BadXdrException("bad value: " + _args.opopen.openhow.how.mode);
			    }

			    Optional<mode4> createMode = attributeMap.get(nfs4_prot.FATTR4_MODE);
			    if (createMode.isPresent()) {
				mode = createMode.get().value;
				appliedAttribytes.set(nfs4_prot.FATTR4_MODE);
			    }

                            Optional<fattr4_size> createSize = attributeMap.get(nfs4_prot.FATTR4_SIZE);
                            if (createSize.isPresent() && createSize.get().value == 0) {
                                appliedAttribytes.set(nfs4_prot.FATTR4_SIZE);
                            }

                            _log.debug("Creating a new file: {}", name);
                            inode = context.getFs().create(context.currentInode(), Stat.Type.REGULAR,
                                    name, context.getUser().getUID(),
                                    context.getUser().getGID(), mode);

                            res.resok4.cinfo.after = new changeid4(System.currentTimeMillis());
                        } catch (FileExistsChimeraFsException e) {

                            if (exclusive) {
                                throw new ExistException();
                            }
                            // no changes from us, old stat info is still good enough
                            res.resok4.cinfo.after = new changeid4(stat.getCTime());

                            inode = context.getFs().lookup(context.currentInode(), name);
                            if (_log.isDebugEnabled()) {
                                Stat fileStat = context.getFs().getattr(context.currentInode());
                                _log.debug("Opening existing file: {}, uid: {}, gid: {}, mode: 0{}",
                                    name,
                                    fileStat.getUid(),
                                    fileStat.getGid(),
                                    Integer.toOctalString(fileStat.getMode() & 0777));
                            }

                            if (context.getFs().access(inode, nfs4_prot.ACCESS4_MODIFY) == 0) {
                                throw new AccessException();
                            }

                            OperationSETATTR.setAttributes(_args.opopen.openhow.how.createattrs, inode, context);
                        }

                    } else {
                        // no changes from us, old stat info is still good enough
                        res.resok4.cinfo.after = new changeid4(stat.getCTime());

                        inode = context.getFs().lookup(context.currentInode(), name);
                        stat = context.getFs().getattr(inode);

                        if ((_args.opopen.share_access.value & nfs4_prot.OPEN4_SHARE_ACCESS_READ) != 0
                                && (context.getFs().access(inode, nfs4_prot.ACCESS4_READ) == 0)) {
                            throw new AccessException();
                        }

                        if ((_args.opopen.share_access.value & nfs4_prot.OPEN4_SHARE_ACCESS_WRITE) != 0
                                && (context.getFs().access(inode, nfs4_prot.ACCESS4_MODIFY) == 0)) {
                            throw new AccessException();
                        }

                        if (stat.type() == Stat.Type.DIRECTORY) {
                            throw new IsDirException();
                        }

                        if (stat.type() == Stat.Type.SYMLINK) {
                            throw new SymlinkException();
                        }

                        if (stat.type() != Stat.Type.REGULAR) {
                            if (context.getMinorversion() == 0) {
                                throw new SymlinkException();
                            } else {
                                throw new WrongTypeException();
                            }
                        }
                    }

                    context.currentInode(inode);

                    break;
		case open_claim_type4.CLAIM_PREVIOUS:
		    /*
		     * As we don't have persistent state store there are two oprions:
		     *
		     *   1. fail with NFSERR_RECLAIM_BAD
		     *   2. just do a regulat open by FH.
		     *
		     * Let take the second case as first one will endup with
		     * it anyway.
		     *
		     * Just check that we are still in the grace period and
		     * fall -through to CLAIM_FH.
		     */
		    if (context.getStateHandler().hasGracePeriodExpired()) {
			throw new NoGraceException("Server not in grace period");
		    }
		    if (!client.needReclaim()) {
			throw new NoGraceException("CLAIM open after 'reclaim complete'");
		    }
                case open_claim_type4.CLAIM_FH:

                    _log.debug("open by Inode for : {}", context.currentInode());

                    /*
                     * Send some dummy values for cinfo, as client
                     * does not really expect something. We can do a stat on parent,
                     * by this will be an extra fileststem (db) call which client
                     * will not use.
                     */
                    res.resok4.cinfo.before = new changeid4(0);
                    res.resok4.cinfo.after = new changeid4(0);

                    inode = context.currentInode();
                    stat = context.getFs().getattr(inode);

                    if ((_args.opopen.share_access.value == nfs4_prot.OPEN4_SHARE_ACCESS_READ)
                            && (context.getFs().access(inode, nfs4_prot.ACCESS4_READ) == 0)) {
                        throw new AccessException();
                    }

                    if ((_args.opopen.share_access.value == nfs4_prot.OPEN4_SHARE_ACCESS_BOTH
                            || _args.opopen.share_access.value == nfs4_prot.OPEN4_SHARE_ACCESS_WRITE)
                            && (context.getFs().access(inode, nfs4_prot.ACCESS4_MODIFY) == 0)) {
                        throw new AccessException();
                    }

                    if (stat.type() == Stat.Type.DIRECTORY) {
                        throw new IsDirException();
                    }

                    if (stat.type() == Stat.Type.SYMLINK) {
                        throw new SymlinkException();
                    }
                    break;
                case open_claim_type4.CLAIM_DELEGATE_CUR:
                case open_claim_type4.CLAIM_DELEGATE_PREV:
                case open_claim_type4.CLAIM_DELEG_CUR_FH:
                case open_claim_type4.CLAIM_DELEG_PREV_FH:
                    _log.warn("Unimplemented open claim: {}", _args.opopen.claim.claim);
                    throw new InvalException("Unimplemented open claim: {}" + _args.opopen.claim.claim);
                default:
                    _log.warn("BAD open claim: {}", _args.opopen.claim.claim);
                    throw new InvalException("BAD open claim: {}" + _args.opopen.claim.claim);

            }

            /*
             * if it's v4.0, then client have to confirm
             */
            if (context.getMinorversion() > 0) {
                res.resok4.rflags = new uint32_t(nfs4_prot.OPEN4_RESULT_LOCKTYPE_POSIX);
            }else {
                res.resok4.rflags = new uint32_t(nfs4_prot.OPEN4_RESULT_LOCKTYPE_POSIX
                        | nfs4_prot.OPEN4_RESULT_CONFIRM);
            }

            NFS4State nfs4state = client.createState(_args.opopen.seqid.value);
            res.resok4.stateid = nfs4state.stateid();

            _log.debug("New stateID: {}", nfs4state.stateid());

            res.status = nfsstat.NFS_OK;

        } catch (FileExistsChimeraFsException e) {
            _log.debug("OPEN: {}", e.getMessage());
            res.status = nfsstat.NFSERR_EXIST;
        } catch (FileNotFoundHimeraFsException fnf) {
            _log.debug("OPEN: {}", fnf.getMessage());
            res.status = nfsstat.NFSERR_NOENT;
        }
    }
}
