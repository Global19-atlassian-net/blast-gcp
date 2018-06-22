/*===========================================================================
 *
 *                            PUBLIC DOMAIN NOTICE
 *               National Center for Biotechnology Information
 *
 *  This software/database is a "United States Government Work" under the
 *  terms of the United States Copyright Act.  It was written as part of
 *  the author's official duties as a United States Government employee and
 *  thus cannot be copyrighted.  This software/database is freely available
 *  to the public for use. The National Library of Medicine and the U.S.
 *  Government have not placed any restriction on its use or reproduction.
 *
 *  Although all reasonable efforts have been taken to ensure the accuracy
 *  and reliability of the software and data, the NLM and the U.S.
 *  Government do not and cannot warrant the performance or results that
 *  may be obtained by using this software or data. The NLM and the U.S.
 *  Government disclaim all warranties, express or implied, including
 *  warranties of performance, merchantability or fitness for any particular
 *  purpose.
 *
 *  Please cite the author in any work or product based on this material.
 *
 * ===========================================================================
 *
 */
package gov.nih.nlm.ncbi.blastjni;

import java.io.Serializable;
import java.net.InetAddress;

class BLAST_PARTITION implements Serializable
{
    public final Integer nr;
    public final String db_spec;
    public final String name;
    public String worker_name;
    //public Integer[] payload;

    public BLAST_PARTITION( final BLAST_PARTITION other )
    {
        this.nr         = other.nr;
        this.db_spec    = other.db_spec;
        this.name       = other.name;
        this.worker_name= other.worker_name;
        //this.payload    = other.payload;
    }

    // location  : '/tmp/blast/db'
    // db_pat    : 'nt_50M'
    // nr        : 102
    // db_spec --> '/tmp/blast/db/nt_50M.102/nt_50M.102'
    public BLAST_PARTITION( final String location, final String db_pat, final Integer nr, final Boolean flat )
    {
        this.nr = nr;
        //this.payload = new Integer[ 50000 ];

        if ( nr < 100 )
            name = String.format( "%s.%02d", db_pat, nr  );
        else
            name = String.format( "%s.%d", db_pat, nr );

        if ( flat )
            db_spec = String.format( "%s/%s", location, name );
        else
            db_spec = String.format( "%s/%s/%s", location, name, name );

    }

    public BLAST_PARTITION prepare()
    {
        try
        {
            this.worker_name = java.net.InetAddress.getLocalHost().getHostName();
        }
        catch ( Exception e )
        {
            this.worker_name = "unknown";
        }

        return new BLAST_PARTITION( this );
    }

    @Override public String toString()
    {
        return String.format( "part( %d: '%s' '%s')", this.nr, this.name, this.db_spec );
    }

    public Integer getPartition( Integer num_partitions )
    {
        if ( num_partitions > 0 )
            return ( nr % num_partitions );
        else
            return 0;
    }
}

