
document.addEventListener("DOMContentLoaded", (event) => {



    document.querySelector("#myUL").addEventListener("click", (e) => {
	console.log(e.target)
    })
    

    document.querySelectorAll("li").forEach( (x) => {
	x.addEventListener("click", (e) => {
	    //e.stopPropagation();
	    //console.log(e.currentTarget)
	})
    })


    
})

if (DEV) {
    new EventSource('/esbuild').addEventListener('change', () => location.reload())
}
