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
import java.util.List;
import java.util.ArrayList;

public class CONF implements Serializable
{
    public List< CONF_DATABASE > dbs;

    public CONF()
    {
        dbs = new ArrayList<>();
    }

    public Boolean valid()
    {
        Boolean res = true;
        for ( CONF_DATABASE db : dbs )
        {
            if ( res )
                res = db.valid();
        }
        return res;
    }

    public String missing()
    {
        String S = "";
        for ( CONF_DATABASE db : dbs )
        {
            S = S + db.missing();
        }        
        return S;
    }

    @Override public String toString()
    {
        String S = "";
        for ( CONF_DATABASE db : dbs )
        {
            S = S + db.toString();
        }        
        return S;
    }
}

