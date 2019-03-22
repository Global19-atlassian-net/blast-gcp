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
import java.math.BigInteger;

/**
 * Name-Size pair
 *
*/
public final class BC_NAME_SIZE implements Serializable
{
    public final String name;
    public final BigInteger size;

/**
 * create instance of BC_NAME_SIZE
 *
 * @param a_name    name to be stored
 * @param a_size    size to be stored
*/
    BC_NAME_SIZE( final String a_name, BigInteger a_size )
    {
        name = a_name;
        size = a_size;
    }

  /**
   * getter for use by test-code
   *
   * @return returns the object as string
   */
    @Override public String toString()
    {
        return String.format("%s,%d bytes", name , size);
    }

}
