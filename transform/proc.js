
const { open, readFile, writeFile } = await import("node:fs/promises");
const data = await readFile('../data/1.04.02.xml','utf8');
const { parseXml } = await import("@rgrove/parse-xml");

console.log("starting...")

const ead = parseXml(data);

const dsc = ead.children[0].
      children.filter( (x => x["name"] == "ListRecords"))[0].
      children.filter( (x => x["name"] == "record"))[0].
      children.filter( (x => x["name"] == "metadata"))[0].
      children.filter( (x => x["name"] == "ead"))[0].
      children.filter( (x => x["name"] == "archdesc"))[0].
      children.filter( (x => x["name"] == "dsc"))[0];

const proc = (c, parents = []) => {

    const level = c.attributes["level"];
    
    const attrs = c.children.filter( (x => x["name"] == "did"))[0]
          ?.children.filter( (x => x["name"] == "unittitle" || x["name"] == "unitid" ))
	  .map (x => (
	      {  "name": x["name"], 
		 "label": x.children[0].text,
		 "attr": x.attributes
	      }
	  ));
    
    const uuid = attrs?.filter(x => x["name"] == "unitid" && x.attr["type"] == "urn:uuid")?.[0].label;
    const title = attrs?.filter(x => x["name"] == "unittitle")?.[0].label;
    
    const doc = [{"type": level, "uuid": uuid, "title": title, "parents": parents}];
    
    const newparents = parents.slice(0);
    if (uuid)
	newparents.push(uuid);
    
    const childs  = c.children.filter( (x => x["name"] == "c"))
    	  .flatMap((x) => proc(x, newparents));
    
    return doc.concat(childs);
}


const list = proc(dsc);

const jsonstr = JSON.stringify(list, null,  "\t");
const prom = writeFile("output.json", jsonstr);

console.log("done");
