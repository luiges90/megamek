/*
 * MegaMek - Copyright (C) 2000-2002 Ben Mazur (bmazur@sev.org)
 *
 *  This program is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the Free
 *  Software Foundation; either version 2 of the License, or (at your option)
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 */

/*
 * BuildingBlock.java
 *
 * Created on April 2, 2002, 1:57 PM
 */

/**
 *
 * @author  Nate Rowden
 * @version 1
 */

package megamek.common.util; //add to this package so BLKMechFile can read it's files...

import java.io.*;
import com.sun.java.util.collections.*;


/** buildingBlock is based on a file format I used in an
 * online game.  The original was written in PHP,
 * this one is more robust, and written in Java.
 */
public class BuildingBlock {

    private Vector rawData;
    private static final int version = 1;
    private static final char comment = '#';
        /** Creates new empty buildingBlock
         */
    public BuildingBlock() {
    
    
        //for holding the file we read/parse
        rawData = new Vector();
        
    
    }

 
    /** Creates a new buildingBlock and fills it with the data in the String[] array.
     * @param data This is most usefull for storing one block file inside another...but <I>data</I>
     * can be an array of anything...such as comments.
     */    
    public BuildingBlock(String[] data) {
        rawData = new Vector();

        rawData = this.makeVector(data);
        
                
    }    
    
    
    /** Creates a new buildingBlock and fills it with the Vector.
     * @param data The Vector can be filled with anything.
     */    
    public BuildingBlock(Vector data) {
        
        rawData = data;
    }
    
    
    /** Creates a buildingBlock and fills it with the data inside the given <I>fileName</I>.
     * @param fileName This should be a buildingBlock data file.
     */    
    public BuildingBlock(String fileName) {
        rawData = new Vector();
        this.readFile(fileName);
    
    }
    
    //reads the file passed to it...
    public BuildingBlock(File file) {
        rawData = new Vector();
        
        
        this.readFile(file.getPath());
    
    }
    
    
    /** Loads a buildingBlock datafile.
     * @param fileName The name of the file to load.
     * @return Returns true on success, and false on failure.
     */    
    public boolean readFile(String fileName) {
     
        String data;
        BufferedReader in;
        
        File file = new File(fileName);
        
        try {
            in = new BufferedReader(new FileReader(file));
        }catch (FileNotFoundException fnfe) {
         
            System.err.println("File "+fileName+" not found");
            return false;
            
        }
        
        //empty the rawData holder...
        rawData.clear();
        
        try {
            
            
            //read the file till can't read no more...
            while(in.ready()) {
            
                data = in.readLine();
                if (data == null) continue; 
                data = data.trim();
                
                //check for blank lines & comment lines...
                //don't add them to the rawData if they are
                if (data.length() > 0 && !data.startsWith(""+this.comment)) 
                    this.rawData.add(data);
                
                                
            
            };
            
        }catch (IOException e) {
            
            System.err.println("An IO Exception occured while attempting to read "+fileName);
            return false;
            
        }
        
        
        return true;
    }

    
    /** Finds the starting index of a block.  This is used by the class to locate data,
     * but is a public function that can be useful if you know what you want to do with
     * the <CODE>rawData</CODE> Vector.
     * @param blockName The name of the data block to locate.
     * @return Returns the start index of the block data.  Or -1 if not found.
     * @see findEndIndex()
     * @see getAllDataAsVector()
     */    
    public int findStartIndex(String blockName) {
     
       String line;
       int startIndex = -1;
        
       //look for the block...
        for (int lineNum = 0; lineNum < rawData.size(); lineNum++) {
          
            line = rawData.get(lineNum).toString();
            
            //look for "<blockName>"
            try {
            if (line.length() >=3 && line.substring(1,line.length()-1).equalsIgnoreCase(blockName)) {
                startIndex = ++lineNum; 
                break;
            }
            }catch(StringIndexOutOfBoundsException e) {
             
                System.err.println("Was looking for <"+blockName+"> and caught a");
                System.err.println("string index out of bounds exception on line: \""+line+"\"");
                System.err.println("rawData index number: "+lineNum);
                                
            }
            
            
        };
        
        //if (startIndex == -1) System.err.println("Could not locate <"+blockName+">");

        return startIndex;
        
    }
    
   
    /** Finds the starting index of a block.  This is used by the class to locate data,
     * but is a public function that can be useful if you know what you want to do with
     * the <CODE>rawData</CODE> Vector.
     * @param blockName The name of the data block to locate.
     * @return Returns the end index of the block data.  Or -1 if not found.
     * @see findStartIndex()
     * @see getAllDataAsVector()
     */    
    public int findEndIndex(String blockName) {
         String line;
        int endIndex = -1;
        
        //look for the block...
        for (int lineNum = 0; lineNum < rawData.size(); lineNum++) {
          
            line = rawData.get(lineNum).toString();

            //look for "</blockName>"
            try {
            if (line.length() >=3 && line.substring(1,line.length()-1).equalsIgnoreCase("/"+blockName)) {
                endIndex = lineNum;
                break;
            }
            } catch(StringIndexOutOfBoundsException e) {
                
                System.err.println("Was looking for </"+blockName+"> and caught a");
                System.err.println("string index out of bounds exception on line: \""+line+"\"");
                System.err.println("rawData index number: "+lineNum);
                
            }
            
        };
   
        
        //if (endIndex == -1) System.err.println("Could not locate </"+blockName+">");
            
        return endIndex;
    }
    
    
    /** Gets data from inside a block.
     * @param blockName The name of the block to grab the data from.
     * @return Returns an array of data.  
     */    
    public String[] getDataAsString(String blockName) {
        
        String [] data;
        String line;
        int startIndex = 0, endIndex = 0;
        
        startIndex = this.findStartIndex(blockName);
        
        endIndex = this.findEndIndex(blockName);
        
        if (startIndex == -1 || endIndex == -1) {
         
            data = new String[1];
            data[0] = "0";
            return data;
            
        }
        
        //calculate the size of our data array by subtracting the two indexes ...
        int size = endIndex - startIndex;
    
        if (size == 0) data = new String[size+1];  //add one so we always have at least a size 1 array...
        else data = new String[size];
        
        
        
        int dataRecord = 0;
        
        //fill up the data array with the raw data we want...
        for ( int rawRecord = startIndex; rawRecord < endIndex; rawRecord++) {
         
            
            data[dataRecord] = rawData.get(rawRecord).toString();
            dataRecord++;
        }
        
        return data; //hand back the goods...

    }

    
    /**
     * @see getDataAsString()
     */    
    public int[] getDataAsInt(String blockName) {
        
        int [] data;
        int startIndex, endIndex;
        
        startIndex = this.findStartIndex(blockName);
        
        endIndex = this.findEndIndex(blockName);
        
        if (startIndex == -1 || endIndex == -1) {
         
            data = new int[1];
            data[0] = 0;
            return data;
            
        }
        
        
        //calculate the size of our data array by subtracting the two indexes ...
        
        int size = endIndex - startIndex;
    
        if (size == 0)  data = new int[size+1];  //add one so we always have at least a size 1 array...
        else data = new int[size];
        
        
        int dataRecord = 0;
        
        //fill up the data array with the raw data we want...
        for ( int rawRecord = startIndex; rawRecord < endIndex; rawRecord++) {
         
        
            try {
            
                data[dataRecord] = Integer.parseInt(rawData.get(rawRecord).toString());
                dataRecord++;
            }catch (NumberFormatException oops) {
             
                data[0] = 0;
                System.err.println("getDataAsInt(\""+blockName+"\") failed.  NumberFormatException was caught.");
                
            }
            
        }
        
        return data; //hand back the goods...

    }
    
    
    /**
     * @see getDataAsString()
     */    
    public float[] getDataAsFloat(String blockName) {
        
        float [] data;
        int startIndex, endIndex;
        
        startIndex = this.findStartIndex(blockName);
        
        endIndex = this.findEndIndex(blockName);
        
        if (startIndex == -1 || endIndex == -1) {
         
            data = new float[1];
            data[0] = 0;
            return data;
            
        }
        
        
        //calculate the size of our data array by subtracting the two indexes ...
        
        int size = endIndex - startIndex;
    
        if (size == 0) data = new float[size+1]; //add one so we always have at least a size 1 array...
        else  data = new float[size];  
            
        
        
        int dataRecord = 0;
        
        //fill up the data array with the raw data we want...
        for ( int rawRecord = startIndex; rawRecord < endIndex; rawRecord++) {
         
            try {
            
                data[dataRecord] = Float.valueOf(rawData.get(rawRecord).toString()).floatValue();
                dataRecord++;
        
            }catch (NumberFormatException oops) {
             
                data[0] = 0;
                System.err.println("getDataAsFloat(\""+blockName+"\") failed.  NumberFormatException was caught.");
                
            }
            
        }
        
        return data; //hand back the goods...

    }
    
    
    /** Gets data from a block.
     * @param blockName Name of the block to get data from.
     * @return Returns the data as a Vector.
     */    
    public Vector getDataAsVector(String blockName) {
        
        Vector data;
        String line;
        int startIndex = 0, endIndex = 0;
        
        startIndex = this.findStartIndex(blockName);
        
        endIndex = this.findEndIndex(blockName);
        
        if (startIndex == -1 || endIndex == -1) {
         
            data = new Vector();
            data.clear();
            return data;
            
        }
        
        //calculate the size of our data array by subtracting the two indexes ...
        int size = endIndex - startIndex;
    
        data = new Vector();
        
                        
        //fill up the data vector with the raw data we want...
        for ( int rawRecord = startIndex; rawRecord < endIndex; rawRecord++) {
         
          
            data.add(rawData.get(rawRecord));
            
        }
        
        return data; //hand back the goods...

    }
    
    
    
    
    /** Clears the <CODE>rawData</CODE> Vector and inserts a default comment and <I>BlockVersion</I>
     * information.
     * @return Returns true on success.
     */    
    public boolean createNewBlock() {
        
     
        rawData.clear();
                
        this.writeBlockComment("building block data file");
        rawData.add(new String("")); //blank line..
    
        this.writeBlockData("BlockVersion", ""+this.version);
        
        return true;
    }
    
    //to make life easier...
    
    /**
     * @see writeBlockData (String, Vector)
     */    
    public boolean writeBlockData(String blockName, String blockData) {
     
        String [] temp = new String[1];
        temp[0] = blockData;
        
        return writeBlockData(blockName, this.makeVector(temp)); 
        
    }
    
    /**
     * @see writeBlockData (String, Vector)
     */    
    public boolean writeBlockData(String blockName, int blockData) {
     
        String [] temp = new String[1];
        temp[0] = ""+blockData;
        return writeBlockData(blockName, this.makeVector(temp)); 
        
    }
    /**
     * @see writeBlockData (String, Vector)
     */    
    public boolean writeBlockData(String blockName, int [] blockData) {
     
        String [] temp = new String[blockData.length];
            
            for (int c = 0; c < blockData.length; c++) {
             
                temp[c] = ""+blockData[c];
                
            };
        return writeBlockData(blockName, this.makeVector(temp));    
        
    }
    
    /**
     * @see writeBlockData (String, Vector)
     */    
    public boolean writeBlockData(String blockName, float blockData) {
     
        String [] temp = new String[1];
        temp[0] = ""+blockData;
        return writeBlockData(blockName, this.makeVector(temp)); 
        
    }
    
    
    /**
     * @see writeBlockData (String, Vector)
     */    
    public boolean writeBlockData(String blockName, float [] blockData) {
     
        String [] temp = new String[blockData.length];
        
            
            for (int c = 0; c < blockData.length; c++) {
             
                temp[c] = ""+blockData[c];
                
            };
        
            return writeBlockData(blockName, this.makeVector(temp));
        
    }
    
    /**
     * @see writeBlockData (String, Vector)
     */    
public boolean writeBlockData(String blockName, String [] blockData) {
    
            return writeBlockData(blockName, this.makeVector(blockData));
       
    }

    /** Writes a data block to the <CODE>rawData</CODE> vector.
     * @param blockName Name of the block to be created.
     * @param blockData Data to be written inside the block.
     * @return Returns true on success.
     */    
    public boolean writeBlockData(String blockName, Vector blockData) {
    
        rawData.add(new String("<"+blockName+">"));
        
        for (int c = 0; c < blockData.size(); c++) {
        
            //
            rawData.add(new String(blockData.get(c).toString().trim()));
        
        };
        
        rawData.add(new String("</"+blockName+">"));
        rawData.add(new String(""));
        
        return true;
        
    }

    /** Writes a comment.
     * @param theComment The comment to be written.
     * @return Returns true on success.
     */    
    public boolean writeBlockComment(String theComment) {
     
        rawData.add(this.comment+theComment);
        return true;
        
    }
    
    
    /** Writes the buildingBlock data to a file.
     * @param fileName File to write.  Overwrites existing files.
     * @return Returns true on success.
     */    
    public boolean writeBlockFile(String fileName) {
     
        File file = new File(fileName);
        
        if (file.exists()) {
            if (!file.delete()) {
             
                System.err.println("Unable to delete file...(so I could re-write it)");
                return false;
            }
        }
        
        try {
            
//        file.createNewFile();
        
        
        BufferedWriter out = new BufferedWriter(new FileWriter(file));
        
        
        
            for (int c = 0; c < rawData.size(); c++) {
                
             
                
                out.write(rawData.get(c).toString());
                out.newLine();
                
                
            }
                
            out.flush();
            out.close();
        } catch (IOException e) {
         
            System.err.println("Unable to save block file "+fileName);
            return false;
        }
        
        return true;
    }
    
    
    /** Clears the <CODE>rawData</CODE> Vector.
     */    
    public void clearData() {
     
        rawData.clear();
        
    }
    
    /** Gets the size of the <CODE>rawData</CODE> Vector.
     * @return Returns <CODE>rawData.size()</CODE>
     */    
    public int dataSize() {
        
     
        return rawData.size();
        
    }
    
    /** Converts a String array into a Vector.
     * @param stringArray The String array to convert.
     * @return Returns the Vector created by the String[]
     */    
    public Vector makeVector(String [] stringArray) {
     
        Vector newVect = new Vector();
        int c=0;
        
        try {
         
            for (c = 0; c < stringArray.length; c++) {
        
                //this should throw an expection when we hit the end
                stringArray[c] = stringArray[c].trim();
                
                newVect.add(stringArray[c]);
                
            };
            
        } catch(ArrayIndexOutOfBoundsException e) {
         
            //we're done...return the vector
            return newVect;
            
        }
        
        return newVect; //just to make sure ; -?
        
    }
    
    /** Useful if you want to copy one buildingBlock into another.
     * @return Returns the <CODE>rawData</CODE> Vector.
     */    
    public Vector getVector() {
     
        return this.rawData;
        
    }
    
    /** Gets all the data inside the <CODE>rawData</CODE> Vector.
     * @return Returns the data as a String array
     */    
    public String[] getAllDataAsString() {
        
        String[] theData = new String[this.rawData.size()];
        
        for (int c = 0; c < this.rawData.size(); c++ ) {
         
            theData[c] = this.rawData.get(c).toString();
            
        }
        
        return theData;
        
    }
    
    /** Just about the same as the <CODE>getVector()</CODE> command.
     * @see getVector ()
     * @return Returns the <CODE>rawData</CODE> Vector.
     */    
    public Vector getAllDataAsVector() {
        
        Vector theData = this.rawData; //can I jsut return this?
        
        return theData;
        
    }
    
    
    /** Tells you the size of an array this thing returned by giving you the number in the
     * [0] position.
     * @param array The array to get the size of.
     * @return Returns the number in the [0] position.
     */    
    public int getReturnedArraySize(String []array) {
        
        
        try {
        
            return Integer.parseInt(array[0]);
        
        }catch( NumberFormatException e) {
         
            //couldn't parse it...
            System.err.println("Couldn't find array size at [0]...is this an array I returned...?");
            System.err.println("Trying to find size anyway...");
            return this.countArray(array);
        }
        
        
    }
    
//for those of us who like doing things indirectly ; -?    
    /**
     * @see getReturnedArraySize (String[])
     */    
public int getReturnedArraySize(int []array) {
        
        
            return array[0];
        
        
    }

    /**
     * @see getReturnedArraySize (String[])
     * @return Returns <CODE>array.size()</CODE>
     */    
    public int getReturnedArraySize(Vector array) {
        
        
            return array.size();
        
        
    }
    
    /**
     * @see getReturnedArraySize (String[])
     */    
    public int getReturnedArraySize(float []array) {
        
        try{
            return Integer.parseInt(""+array[0]);
        }catch(NumberFormatException e) {
            
            System.err.println("Couldn't find array size at [0]...is this an array I returned...?");
            System.err.println("Trying to find size anyway...");
            return this.countArray(array);
        }
        
  
    }

    /** Counts the size of an array.
     * @param array The array to count.
     * @return Returns the array's size.
     */    
    public int countArray(String[] array) {
        
        return array.length;
    
    }
        
    /**
     * @see countArray( String[] )
     */    
    public int countArray(float[] array) {
        
        return array.length;
    }
    

    /**
     * @see countArray( String[] )
     */    
    public int countArray(int[] array) {
        
            return array.length;
    }

    /**
     *Checks to see if a block exists...returns true or false
     */
    public boolean exists(String blockName) {
       
        if (this.findStartIndex(blockName) == -1) return false;
        if (this.findEndIndex(blockName) == -1) return false;
        
        return true;
    }
}
