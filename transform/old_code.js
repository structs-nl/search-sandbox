const { S3Client, DeleteBucketCommand, paginateListObjectsV2, GetObjectCommand} = await import( "@aws-sdk/client-s3");
const { open, readFile, writeFile } = await import("node:fs/promises");


const getFileTxt = async (id, client) => {

    const txtpages = []

    const paginator = paginateListObjectsV2({ client: client },{ Bucket: "globalise-data", Prefix: "pagexml/NL-HaNA/1.04.02/3598/NL-HaNA_1.04.02_3598"});
    
    for await (const page of paginator) {

	for (const object of page.Contents) {
		
	    // TODO: only get the jsonld files
	    
	    const comm = new GetObjectCommand({ Bucket: "globalise-data", Key: object["Key"]})
	    const response = await client.send(comm);
	    const xmlStr = await response.Body.transformToString();

	    // get the page body

	}
    }

    return txtpages
}





const outputDot = (filename, list) => {
    
    const wrap = x => "\"" + x + "\""
    const esc = x =>  x != null ? (x.replaceAll('"', '\\"')) : x;

    const dotlines = []

    dotlines.push("digraph act {")
    dotlines.push("node [shape=box];")

    for (const node of list) {
	if (node["type"] != "file") {
	    dotlines.push( wrap(node["uuid"]) + " [label=\"" + esc(node["title"]) + "\"];" )
	    const lastparent = node["parents"][node["parents"].length - 1]    
	    dotlines.push(wrap(lastparent) + " -> " + wrap(node["uuid"]) + ";")
	}
    }
    
    dotlines.push("}")
    
    const dot = dotlines.join("\n")
    writeFile(filename, dot)
    
}
