package com.o7solutions.task

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewbinding.ViewBinding
import com.o7solutions.task.database.DatabaseDB
import com.o7solutions.task.database.ImageEntity
import com.o7solutions.task.databinding.FragmentSavedListBinding
import io.appwrite.Client
import io.appwrite.services.Storage

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [SavedListFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class SavedListFragment : Fragment(), ImageListAdapter.OnItemClick {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null
    private lateinit var binding: FragmentSavedListBinding
    private lateinit var adapter: ImageListAdapter
    private var list: ArrayList<ImageEntity> = ArrayList()
    private lateinit var db: DatabaseDB
    lateinit var storage: Storage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentSavedListBinding.inflate(layoutInflater)
        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        db = DatabaseDB.getInstance(requireContext())
        list = db.databaseDao().getAllImages() as ArrayList<ImageEntity>

        binding.apply {

            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = ImageListAdapter(list,this@SavedListFragment)
        }
    }

    override fun onItemClick(position: Int) {

        val bundle = Bundle()
        bundle.putString("uri",list[position].path)
        findNavController().navigate(R.id.viewFragment,bundle)
    }

    override fun upload(position: Int) {
    }


    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment SavedListFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            SavedListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}