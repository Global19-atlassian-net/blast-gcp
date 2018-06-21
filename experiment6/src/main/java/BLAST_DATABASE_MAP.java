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

import java.util.HashMap;
import java.util.Collection;

import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.api.java.JavaSparkContext;

class BLAST_DATABASE_MAP
{
    private final HashMap< String, BLAST_DATABASE > databases;

    public BLAST_DATABASE_MAP( final BLAST_SETTINGS settings,
                               final Broadcast< BLAST_LOG_SETTING > LOG_SETTING,
                               final JavaSparkContext sc )
    {

        databases = new HashMap<>();

        BLAST_YARN_NODES nodes = new BLAST_YARN_NODES();

        Collection< BLAST_DB_SETTING > col = settings.dbs.values();
        for ( BLAST_DB_SETTING db_setting : col )
        {
            BLAST_DATABASE db = new BLAST_DATABASE( settings, LOG_SETTING, sc, nodes, db_setting );
            databases.put( db.key, db );
            /* to make lookup on just the first part possible */
            databases.put( db.key.substring( 0, 2 ), db );            
        }
    }

    public BLAST_DATABASE get( final String key )
    {
        BLAST_DATABASE res = databases.get( key );
        if ( res == null )
            res = databases.get( key.substring( 0, 2 ) );        
        return res;
    }
}
