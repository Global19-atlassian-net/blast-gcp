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

public class BLAST_YARN_NODE_ITER
{
    private final BLAST_YARN_NODES nodes;
    private int idx;

    BLAST_YARN_NODE_ITER( final BLAST_YARN_NODES a_nodes )
    {
        this.nodes = a_nodes;
        this.idx = 0;
    }

    BLAST_YARN_NODE_ITER( final BLAST_YARN_NODE_ITER other )
    {
        this.nodes = other.nodes;
        this.idx = other.idx;
    }

    public String get()
    {
        String res = nodes.getHost( idx );
        advance();
        return res;
    }

    public void advance()
    {
        idx++;
        if ( idx >= nodes.count() )
            idx = 0;
    }
}
